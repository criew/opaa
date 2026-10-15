package io.opaa.compliance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import liquibase.change.Change;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.exception.LiquibaseException;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;

/**
 * The tables and columns the Liquibase changelog leaves behind, read from its SQL without a
 * database: {@code CREATE TABLE}, {@code ALTER TABLE ... ADD/DROP/RENAME [COLUMN]}, {@code ALTER
 * TABLE ... RENAME TO} and {@code DROP TABLE}, folded in master order. Partitions count as their
 * parent. Fails closed: a change that is no {@code sql:} block (ADR-0034), an {@code ALTER TABLE}
 * action or {@code CREATE TABLE} form it does not know, or table DDL inside a procedural body other
 * than partition maintenance throws instead of being skipped. {@code ChangelogSchemaParityTest}
 * compares the result with the catalog of a migrated database.
 */
public final class ChangelogSchema {

  /** The master changelog on the classpath. */
  public static final String MASTER = "db/changelog/db.changelog-master.yaml";

  private static final String IDENTIFIER = "(\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";

  private static final Pattern CREATE_TABLE =
      Pattern.compile(
          "CREATE\\s+(?:(?:GLOBAL|LOCAL)\\s+)?(?:TEMP\\s+|TEMPORARY\\s+|UNLOGGED\\s+)?TABLE\\s+"
              + "(?:IF\\s+NOT\\s+EXISTS\\s+)?"
              + IDENTIFIER
              + "\\s*(.*)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern ALTER_TABLE =
      Pattern.compile(
          "ALTER\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?" + IDENTIFIER + "\\s+(.*)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern DROP_TABLE =
      Pattern.compile(
          "DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?(.*?)(?:\\s+(?:CASCADE|RESTRICT))?",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern RENAME_TABLE =
      Pattern.compile("RENAME\\s+TO\\s+" + IDENTIFIER, Pattern.CASE_INSENSITIVE);

  private static final Pattern ADD_COLUMN =
      Pattern.compile(
          "ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?" + IDENTIFIER + "\\b.*",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern DROP_COLUMN =
      Pattern.compile(
          "DROP\\s+(?:COLUMN\\s+)?(IF\\s+EXISTS\\s+)?"
              + IDENTIFIER
              + "(?:\\s+(?:CASCADE|RESTRICT))?",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern RENAME_COLUMN =
      Pattern.compile(
          "RENAME\\s+(?:COLUMN\\s+)?" + IDENTIFIER + "\\s+TO\\s+" + IDENTIFIER,
          Pattern.CASE_INSENSITIVE);

  /** Actions that add, drop or rename a constraint rather than a column. */
  private static final Pattern CONSTRAINT_ACTION =
      Pattern.compile(
          "(?:ADD|DROP|RENAME)\\s+(?:CONSTRAINT|PRIMARY\\s+KEY|UNIQUE|CHECK|FOREIGN\\s+KEY|EXCLUDE)\\b.*",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** Actions that leave the set of tables and columns as it is. */
  private static final Pattern NEUTRAL_ACTION =
      Pattern.compile(
          "(?:ALTER|OWNER\\s+TO|ENABLE|DISABLE|FORCE|NO\\s+FORCE|SET|RESET|VALIDATE|CLUSTER"
              + "|REPLICA\\s+IDENTITY)\\b.*",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** Elements of a {@code CREATE TABLE} body that are no column. */
  private static final Pattern TABLE_CONSTRAINT =
      Pattern.compile(
          "(?:CONSTRAINT|PRIMARY\\s+KEY|UNIQUE|CHECK|FOREIGN\\s+KEY|EXCLUDE)\\b.*",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern BODY_CREATE_TABLE =
      Pattern.compile(
          "CREATE\\s+(?:UNLOGGED\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\\S+\\s+(PARTITION\\s+OF)?",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern BODY_DROP_TABLE =
      Pattern.compile("DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(%)?", Pattern.CASE_INSENSITIVE);

  private static final Pattern BODY_COLUMN_DDL =
      Pattern.compile(
          "\\b(?:ADD|DROP|RENAME)\\s+COLUMN\\b|\\bRENAME\\s+TO\\b", Pattern.CASE_INSENSITIVE);

  /** A table with its columns in creation order, each mapped to the file that added it. */
  public record Table(String name, String file, Map<String, String> columns) {

    public Table {
      columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }
  }

  private final Map<String, String> tableFiles = new LinkedHashMap<>();
  private final Map<String, Map<String, String>> columns = new LinkedHashMap<>();
  private String file;

  private ChangelogSchema() {}

  /** The schema of the master changelog on the classpath. */
  public static Map<String, Table> read() {
    ChangelogSchema schema = new ChangelogSchema();
    for (ChangeSet changeSet : changeSets(MASTER)) {
      schema.file = changeSet.getFilePath();
      for (Change change : changeSet.getChanges()) {
        if (!(change instanceof RawSQLChange sql)) {
          throw new IllegalStateException(
              "changeSet "
                  + changeSet.getId()
                  + " in "
                  + schema.file
                  + " uses the change type "
                  + change.getClass().getSimpleName()
                  + "; changesets are sql: blocks (ADR-0034), the compliance guard reads only"
                  + " those");
        }
        schema.apply(sql.getSql());
      }
    }
    return schema.tables();
  }

  /** The schema {@code sql} leaves behind on an empty database, attributed to {@code file}. */
  static Map<String, Table> of(String file, String sql) {
    ChangelogSchema schema = new ChangelogSchema();
    schema.file = file;
    schema.apply(sql);
    return schema.tables();
  }

  private Map<String, Table> tables() {
    Map<String, Table> tables = new LinkedHashMap<>();
    columns.forEach(
        (table, tableColumns) ->
            tables.put(table, new Table(table, tableFiles.get(table), tableColumns)));
    return tables;
  }

  private static List<ChangeSet> changeSets(String master) {
    ResourceAccessor accessor = new ClassLoaderResourceAccessor();
    try {
      DatabaseChangeLog changeLog =
          ChangeLogParserFactory.getInstance()
              .getParser(master, accessor)
              .parse(master, new ChangeLogParameters(), accessor);
      return changeLog.getChangeSets();
    } catch (LiquibaseException e) {
      throw new IllegalStateException("cannot parse " + master, e);
    }
  }

  private void apply(String sql) {
    SqlText text = SqlText.of(sql);
    for (String body : text.bodies()) {
      checkProceduralBody(body);
    }
    for (String statement : text.statements()) {
      applyStatement(statement.strip());
    }
  }

  private void applyStatement(String statement) {
    Matcher create = CREATE_TABLE.matcher(statement);
    if (create.matches()) {
      createTable(name(create.group(1)), create.group(2).strip(), statement);
      return;
    }
    Matcher alter = ALTER_TABLE.matcher(statement);
    if (alter.matches()) {
      alterTable(alter.group(1) != null, name(alter.group(2)), alter.group(3).strip(), statement);
      return;
    }
    Matcher drop = DROP_TABLE.matcher(statement);
    if (drop.matches()) {
      for (String table : splitTopLevel(drop.group(2))) {
        String name = name(table.strip());
        if (columns.remove(name) == null && drop.group(1) == null) {
          throw unknown("table " + name, statement);
        }
        tableFiles.remove(name);
      }
    }
  }

  private void createTable(String table, String rest, String statement) {
    if (rest.regionMatches(true, 0, "PARTITION OF", 0, "PARTITION OF".length())) {
      return;
    }
    if (!rest.startsWith("(")) {
      throw unsupported("CREATE TABLE form", statement);
    }
    int close = matchingParenthesis(rest);
    String after = rest.substring(close + 1).strip();
    if (!after.isEmpty() && !after.regionMatches(true, 0, "PARTITION BY", 0, 12)) {
      throw unsupported("CREATE TABLE clause '" + after + "'", statement);
    }
    if (columns.containsKey(table)) {
      if (Pattern.compile("^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS", Pattern.CASE_INSENSITIVE)
          .matcher(statement)
          .find()) {
        return;
      }
      throw unsupported("second CREATE TABLE of " + table, statement);
    }
    Map<String, String> tableColumns = new LinkedHashMap<>();
    for (String element : splitTopLevel(rest.substring(1, close))) {
      String trimmed = element.strip();
      if (trimmed.isEmpty() || TABLE_CONSTRAINT.matcher(trimmed).matches()) {
        continue;
      }
      if (trimmed.regionMatches(true, 0, "LIKE", 0, 4)) {
        throw unsupported("CREATE TABLE ... (LIKE ...)", statement);
      }
      Matcher column = Pattern.compile(IDENTIFIER).matcher(trimmed);
      if (!column.lookingAt()) {
        throw unsupported("column definition '" + trimmed + "'", statement);
      }
      tableColumns.put(name(column.group(1)), file);
    }
    columns.put(table, tableColumns);
    tableFiles.put(table, file);
  }

  private void alterTable(boolean ifExists, String table, String actions, String statement) {
    Map<String, String> tableColumns = columns.get(table);
    if (tableColumns == null) {
      if (ifExists) {
        return;
      }
      throw unknown("table " + table, statement);
    }
    Matcher renameTable = RENAME_TABLE.matcher(actions);
    if (renameTable.matches()) {
      String target = name(renameTable.group(1));
      columns.put(target, columns.remove(table));
      tableFiles.put(target, tableFiles.remove(table));
      return;
    }
    for (String raw : splitTopLevel(actions)) {
      String action = raw.strip();
      if (CONSTRAINT_ACTION.matcher(action).matches() || NEUTRAL_ACTION.matcher(action).matches()) {
        continue;
      }
      Matcher add = ADD_COLUMN.matcher(action);
      Matcher drop = DROP_COLUMN.matcher(action);
      Matcher rename = RENAME_COLUMN.matcher(action);
      if (add.matches()) {
        tableColumns.putIfAbsent(name(add.group(1)), file);
      } else if (drop.matches()) {
        String column = name(drop.group(2));
        if (tableColumns.remove(column) == null && drop.group(1) == null) {
          throw unknown("column " + table + "." + column, statement);
        }
      } else if (rename.matches()) {
        String source = name(rename.group(1));
        if (tableColumns.remove(source) == null) {
          throw unknown("column " + table + "." + source, statement);
        }
        tableColumns.put(name(rename.group(2)), file);
      } else {
        throw unsupported("ALTER TABLE action '" + action + "'", statement);
      }
    }
  }

  /**
   * Procedural bodies ({@code DO}, functions) may only create partitions and drop tables named at
   * run time - the partition maintenance of the protocol tables; any other table DDL is invisible
   * to this reader and therefore rejected.
   */
  private void checkProceduralBody(String body) {
    Matcher create = BODY_CREATE_TABLE.matcher(body);
    while (create.find()) {
      if (create.group(1) == null) {
        throw unsupported("CREATE TABLE inside a procedural body (only partitions)", body);
      }
    }
    Matcher drop = BODY_DROP_TABLE.matcher(body);
    while (drop.find()) {
      if (drop.group(1) == null) {
        throw unsupported("DROP TABLE of a named table inside a procedural body", body);
      }
    }
    if (BODY_COLUMN_DDL.matcher(body).find()) {
      throw unsupported("column DDL inside a procedural body", body);
    }
  }

  private IllegalStateException unsupported(String what, String statement) {
    return new IllegalStateException(
        file
            + ": the compliance guard cannot read the "
            + what
            + "; write the change as plain DDL or teach ChangelogSchema the form. Statement: "
            + abbreviate(statement));
  }

  private IllegalStateException unknown(String what, String statement) {
    return new IllegalStateException(
        file
            + ": "
            + what
            + " is not known at this point of the changelog. Statement: "
            + abbreviate(statement));
  }

  private static String abbreviate(String statement) {
    String flat = statement.replaceAll("\\s+", " ").strip();
    return flat.length() > 200 ? flat.substring(0, 200) + " ..." : flat;
  }

  private static String name(String identifier) {
    if (identifier.startsWith("\"")) {
      return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
    }
    return identifier.toLowerCase(Locale.ROOT);
  }

  private static int matchingParenthesis(String text) {
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    throw new IllegalStateException("unbalanced parentheses: " + abbreviate(text));
  }

  /** Splits at commas outside parentheses. */
  private static List<String> splitTopLevel(String text) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(text.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(text.substring(start));
    return parts;
  }

  /**
   * SQL split into top-level statements, with comments removed, string literals emptied and
   * dollar-quoted bodies taken out; the bodies are kept apart for {@link #checkProceduralBody}.
   */
  private record SqlText(List<String> statements, List<String> bodies) {

    static SqlText of(String sql) {
      List<String> statements = new ArrayList<>();
      List<String> bodies = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      int i = 0;
      while (i < sql.length()) {
        char c = sql.charAt(i);
        if (c == '-' && sql.startsWith("--", i)) {
          int end = sql.indexOf('\n', i);
          i = end < 0 ? sql.length() : end;
        } else if (c == '/' && sql.startsWith("/*", i)) {
          int end = sql.indexOf("*/", i + 2);
          i = end < 0 ? sql.length() : end + 2;
        } else if (c == '\'') {
          i = skipQuoted(sql, i, '\'');
          current.append("''");
        } else if (c == '"') {
          int end = skipQuoted(sql, i, '"');
          current.append(sql, i, end);
          i = end;
        } else if (c == '$' && dollarTag(sql, i) != null) {
          String tag = dollarTag(sql, i);
          int end = sql.indexOf(tag, i + tag.length());
          if (end < 0) {
            throw new IllegalStateException("unterminated " + tag + " body");
          }
          bodies.add(sql.substring(i + tag.length(), end));
          current.append("$$");
          i = end + tag.length();
        } else if (c == ';') {
          statements.add(current.toString());
          current.setLength(0);
          i++;
        } else {
          current.append(c);
          i++;
        }
      }
      statements.add(current.toString());
      return new SqlText(
          statements.stream().filter(statement -> !statement.isBlank()).toList(), bodies);
    }

    private static int skipQuoted(String sql, int start, char quote) {
      int i = start + 1;
      while (i < sql.length()) {
        if (sql.charAt(i) == quote) {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
            i += 2;
            continue;
          }
          return i + 1;
        }
        i++;
      }
      throw new IllegalStateException("unterminated quote in " + abbreviate(sql));
    }

    private static String dollarTag(String sql, int start) {
      Matcher tag = Pattern.compile("\\$[A-Za-z_]*\\$").matcher(sql);
      tag.region(start, sql.length());
      return tag.lookingAt() ? tag.group() : null;
    }
  }
}
