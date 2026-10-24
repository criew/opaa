package io.opaa.migration;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a still running previous release depends on in the catalog of the current schema: its tables
 * (partitions count as their parent), their columns with type, nullability and default, and their
 * {@code CHECK} constraints. Read before and after one migration, {@link #breakingChangesTo} names
 * every change that release does not survive - from the catalog the database itself built, so the
 * spelling of the SQL, comments, string literals, dynamic SQL in a {@code DO} block and cascades
 * decide nothing. Function bodies only count once they run.
 *
 * <p>Not covered, left to the review: new foreign keys, unique constraints and triggers on existing
 * tables, and what the migration does to existing rows.
 */
final class RollingUpdateCatalog {

  /** The table Flyway keeps for itself; it is no part of the application schema. */
  private static final String SCHEMA_HISTORY = "flyway_schema_history";

  private static final Pattern VARCHAR = Pattern.compile("character varying(?:\\((\\d+)\\))?");

  private static final Pattern ARRAY = Pattern.compile("ARRAY\\[(.*?)\\]", Pattern.DOTALL);

  private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");

  record Table(long oid, String name) {}

  record ColumnKey(long table, int number) {}

  record Column(ColumnKey key, String name, String type, boolean notNull, boolean defaulted) {}

  record CheckKey(long table, String name) {}

  record Check(CheckKey key, String definition, Set<Integer> columns) {}

  private final Map<Long, Table> tables;
  private final Map<ColumnKey, Column> columns;
  private final Map<CheckKey, Check> checks;

  private RollingUpdateCatalog(
      Map<Long, Table> tables, Map<ColumnKey, Column> columns, Map<CheckKey, Check> checks) {
    this.tables = tables;
    this.columns = columns;
    this.checks = checks;
  }

  static RollingUpdateCatalog read(Connection connection) throws SQLException {
    Map<Long, Table> tables = new LinkedHashMap<>();
    Map<ColumnKey, Column> columns = new LinkedHashMap<>();
    Map<CheckKey, Check> checks = new LinkedHashMap<>();
    String ownTables =
        "SELECT c.oid FROM pg_class c WHERE c.relnamespace = current_schema()::regnamespace"
            + " AND c.relkind IN ('r', 'p') AND NOT c.relispartition";
    try (Statement statement = connection.createStatement()) {
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT c.oid, c.relname FROM pg_class c WHERE c.oid IN (" + ownTables + ")")) {
        while (rows.next()) {
          String name = rows.getString(2);
          if (!SCHEMA_HISTORY.equals(name)) {
            tables.put(rows.getLong(1), new Table(rows.getLong(1), name));
          }
        }
      }
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT a.attrelid, a.attnum, a.attname, format_type(a.atttypid, a.atttypmod),"
                  + " a.attnotnull, a.atthasdef OR a.attidentity <> '' OR a.attgenerated <> ''"
                  + " FROM pg_attribute a WHERE a.attnum > 0 AND NOT a.attisdropped"
                  + " AND a.attrelid IN ("
                  + ownTables
                  + ")")) {
        while (rows.next()) {
          ColumnKey key = new ColumnKey(rows.getLong(1), rows.getInt(2));
          if (tables.containsKey(key.table())) {
            columns.put(
                key,
                new Column(
                    key,
                    rows.getString(3),
                    rows.getString(4),
                    rows.getBoolean(5),
                    rows.getBoolean(6)));
          }
        }
      }
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT conrelid, conname, pg_get_constraintdef(oid), conkey FROM pg_constraint"
                  + " WHERE contype = 'c' AND conrelid IN ("
                  + ownTables
                  + ")")) {
        while (rows.next()) {
          CheckKey key = new CheckKey(rows.getLong(1), rows.getString(2));
          if (tables.containsKey(key.table())) {
            checks.put(key, new Check(key, rows.getString(3), numbers(rows.getArray(4))));
          }
        }
      }
    }
    return new RollingUpdateCatalog(tables, columns, checks);
  }

  /**
   * Every change from this catalog to {@code after} that breaks the release still running against
   * it: a table or column dropped or renamed, a column type narrowed or changed, a column made
   * {@code NOT NULL} without a lasting default or made nullable, a default dropped, a new {@code
   * NOT NULL} column without a default, and a {@code CHECK} on existing columns that is new or
   * changed other than by more values in its value lists.
   */
  List<String> breakingChangesTo(RollingUpdateCatalog after) {
    List<String> breaking = new ArrayList<>();
    for (Table table : tables.values()) {
      Table now = after.tables.get(table.oid());
      if (now == null) {
        breaking.add("drops table " + table.name());
      } else if (!now.name().equals(table.name())) {
        breaking.add("renames table " + table.name() + " to " + now.name());
      }
    }
    for (Column column : columns.values()) {
      if (!after.tables.containsKey(column.key().table())) {
        continue;
      }
      String table = tables.get(column.key().table()).name();
      String name = table + "." + column.name();
      Column now = after.columns.get(column.key());
      if (now == null) {
        breaking.add("drops column " + name);
        continue;
      }
      if (!now.name().equals(column.name())) {
        breaking.add("renames column " + name + " to " + now.name());
      }
      if (!now.type().equals(column.type()) && !widens(column.type(), now.type())) {
        breaking.add(
            "changes the type of " + name + " from " + column.type() + " to " + now.type());
      }
      if (!column.notNull() && now.notNull() && !now.defaulted()) {
        breaking.add("sets " + name + " NOT NULL without a lasting default");
      }
      if (column.notNull() && !now.notNull()) {
        breaking.add("drops NOT NULL of " + name + " (the previous release may read NULL)");
      }
      if (column.defaulted() && !now.defaulted()) {
        breaking.add("drops the default of " + name);
      }
    }
    Set<ColumnKey> added = new HashSet<>();
    for (Column column : after.columns.values()) {
      if (!tables.containsKey(column.key().table()) || columns.containsKey(column.key())) {
        continue;
      }
      added.add(column.key());
      if (column.notNull() && !column.defaulted()) {
        breaking.add(
            "adds "
                + tables.get(column.key().table()).name()
                + "."
                + column.name()
                + " NOT NULL without a default");
      }
    }
    for (Check check : after.checks.values()) {
      long table = check.key().table();
      if (!tables.containsKey(table)) {
        continue;
      }
      Check prior = checks.get(check.key());
      if (prior != null
          && (prior.definition().equals(check.definition())
              || admitsAtLeast(check.definition(), prior.definition()))) {
        continue;
      }
      boolean onNewColumnsOnly =
          check.columns().stream().allMatch(number -> added.contains(new ColumnKey(table, number)));
      if (prior == null && onNewColumnsOnly) {
        continue;
      }
      breaking.add(
          (prior == null ? "adds CHECK " : "changes CHECK ")
              + check.key().name()
              + " on existing columns of "
              + tables.get(table).name());
    }
    return breaking;
  }

  /** {@code varchar(n)} to a longer {@code varchar} or to {@code text}: every old value fits. */
  static boolean widens(String before, String after) {
    Matcher from = VARCHAR.matcher(before);
    if (!from.matches()) {
      return false;
    }
    if (after.equals("text")) {
      return true;
    }
    Matcher to = VARCHAR.matcher(after);
    if (!to.matches()) {
      return false;
    }
    if (to.group(1) == null) {
      return true;
    }
    return from.group(1) != null
        && Integer.parseInt(to.group(1)) >= Integer.parseInt(from.group(1));
  }

  /**
   * Whether {@code after} is {@code before} with only more values in its value lists - the same
   * expression with every {@code ARRAY[...]} a superset of the one at its place.
   */
  static boolean admitsAtLeast(String after, String before) {
    if (!ARRAY
        .matcher(before)
        .replaceAll("ARRAY[]")
        .equals(ARRAY.matcher(after).replaceAll("ARRAY[]"))) {
      return false;
    }
    List<Set<String>> beforeLists = valueLists(before);
    List<Set<String>> afterLists = valueLists(after);
    if (beforeLists.size() != afterLists.size() || beforeLists.isEmpty()) {
      return false;
    }
    for (int i = 0; i < beforeLists.size(); i++) {
      if (!afterLists.get(i).containsAll(beforeLists.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static List<Set<String>> valueLists(String definition) {
    List<Set<String>> lists = new ArrayList<>();
    Matcher array = ARRAY.matcher(definition);
    while (array.find()) {
      Set<String> values = new HashSet<>();
      Matcher literal = LITERAL.matcher(array.group(1));
      while (literal.find()) {
        values.add(literal.group(1));
      }
      lists.add(values);
    }
    return lists;
  }

  private static Set<Integer> numbers(Array array) throws SQLException {
    Set<Integer> numbers = new HashSet<>();
    if (array == null) {
      return numbers;
    }
    for (Object number : (Object[]) array.getArray()) {
      numbers.add(((Number) number).intValue());
    }
    return numbers;
  }
}
