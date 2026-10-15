package io.opaa.compliance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * parent; {@code public.} is dropped from a table name, any other schema is rejected.
 *
 * <p>Throws instead of skipping: on a change that is no {@code sql:} block (ADR-0034); on every
 * statement starting with {@code CREATE TABLE}, {@code ALTER TABLE} or {@code DROP TABLE} that it
 * cannot read completely; on {@code CREATE SCHEMA}, {@code CREATE MATERIALIZED VIEW} and top-level
 * {@code SELECT ... INTO}; and in a procedural body ({@code DO}, function) on every {@code CREATE
 * TABLE}, {@code ALTER TABLE}, {@code DROP TABLE}, {@code CREATE SCHEMA} and {@code RENAME} but the
 * partition maintenance on run-time names ({@code %I}, {@code %s}). Dynamic SQL assembled from
 * fragments stays unseen; {@code ChangelogSchemaParityTest} compares the result with the catalog of
 * a migrated database.
 */
public final class ChangelogSchema {

  /** The master changelog on the classpath. */
  public static final String MASTER = "db/changelog/db.changelog-master.yaml";

  /**
   * Tables of the delivered schema no changelog creates, with their columns: Spring AI's {@code
   * PgVectorStore} creates {@code vector_store} at start-up; {@code ChangelogSchemaParityTest}
   * holds the columns against the table it creates.
   */
  public static final Map<String, List<String>> TABLES_OUTSIDE_THE_CHANGELOG =
      Map.of("vector_store", List.of("id", "content", "metadata", "embedding"));

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

  private static final String IDENT = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";

  private static final String IDENTIFIER = "(" + IDENT + ")";

  /** A table name, optionally schema-qualified. */
  private static final String TABLE_NAME = "(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?)";

  private static final Pattern QUALIFIED =
      Pattern.compile(IDENTIFIER + "\\s*\\.\\s*" + IDENTIFIER, Pattern.DOTALL);

  private static final Pattern SIMPLE = Pattern.compile(IDENT, Pattern.DOTALL);

  private static final Pattern STARTS_CREATE_TABLE =
      Pattern.compile(
          "CREATE\\s+(?:(?:GLOBAL|LOCAL)\\s+)?(?:(?:TEMP|TEMPORARY|UNLOGGED)\\s+)?TABLE\\b.*",
          FLAGS);

  private static final Pattern STARTS_ALTER_TABLE = Pattern.compile("ALTER\\s+TABLE\\b.*", FLAGS);

  private static final Pattern STARTS_DROP_TABLE = Pattern.compile("DROP\\s+TABLE\\b.*", FLAGS);

  /** Statements that create or fill tables in a way this reader does not follow. */
  private static final Pattern REJECTED =
      Pattern.compile(
          "(?:CREATE\\s+SCHEMA\\b|CREATE\\s+MATERIALIZED\\s+VIEW\\b|SELECT\\b.*\\bINTO\\b).*",
          FLAGS);

  private static final Pattern CREATE_TABLE =
      Pattern.compile(
          "CREATE\\s+(?:(?:GLOBAL|LOCAL)\\s+)?(?:(?:TEMP|TEMPORARY|UNLOGGED)\\s+)?TABLE\\s+"
              + "(?:IF\\s+NOT\\s+EXISTS\\s+)?"
              + TABLE_NAME
              + "\\s*(.*)",
          FLAGS);

  private static final Pattern ALTER_TABLE =
      Pattern.compile(
          "ALTER\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?" + TABLE_NAME + "\\s+(.*)", FLAGS);

  private static final Pattern DROP_TABLE =
      Pattern.compile(
          "DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?(.*?)(?:\\s+(?:CASCADE|RESTRICT))?", FLAGS);

  private static final Pattern RENAME_TABLE =
      Pattern.compile("RENAME\\s+TO\\s+" + TABLE_NAME, FLAGS);

  private static final Pattern ADD_COLUMN =
      Pattern.compile(
          "ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?" + IDENTIFIER + "(?=\\s|$).*", FLAGS);

  private static final Pattern DROP_COLUMN =
      Pattern.compile(
          "DROP\\s+(?:COLUMN\\s+)?(IF\\s+EXISTS\\s+)?"
              + IDENTIFIER
              + "(?:\\s+(?:CASCADE|RESTRICT))?",
          FLAGS);

  private static final Pattern RENAME_COLUMN =
      Pattern.compile("RENAME\\s+(?:COLUMN\\s+)?" + IDENTIFIER + "\\s+TO\\s+" + IDENTIFIER, FLAGS);

  /** Actions that add, drop or rename a constraint rather than a column. */
  private static final Pattern CONSTRAINT_ACTION =
      Pattern.compile(
          "(?:ADD|DROP|RENAME)\\s+(?:CONSTRAINT|PRIMARY\\s+KEY|UNIQUE|CHECK|FOREIGN\\s+KEY|EXCLUDE)\\b.*",
          FLAGS);

  /** Actions that leave the set of tables and columns as it is. */
  private static final Pattern NEUTRAL_ACTION =
      Pattern.compile(
          "(?:ALTER|OWNER\\s+TO|ENABLE|DISABLE|FORCE|NO\\s+FORCE|SET|RESET|VALIDATE|CLUSTER"
              + "|REPLICA\\s+IDENTITY)\\b.*",
          FLAGS);

  /** Elements of a {@code CREATE TABLE} body that are no column. */
  private static final Pattern TABLE_CONSTRAINT =
      Pattern.compile(
          "(?:CONSTRAINT|PRIMARY\\s+KEY|UNIQUE|CHECK|FOREIGN\\s+KEY|EXCLUDE)\\b.*", FLAGS);

  /** Table DDL in a procedural body; each occurrence must be {@link #BODY_ALLOWED}. */
  private static final Pattern BODY_TABLE_DDL =
      Pattern.compile(
          "\\b(?:CREATE(?:\\s+\\w+){0,3}?\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE|CREATE\\s+SCHEMA"
              + "|RENAME)\\b",
          FLAGS);

  /** The partition maintenance of the protocol tables, on names given at run time. */
  private static final Pattern BODY_ALLOWED =
      Pattern.compile(
          "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?%[Is]\\s+PARTITION\\s+OF\\b"
              + "|ALTER\\s+TABLE\\s+(?:ONLY\\s+)?%[Is]\\s+OWNER\\s+TO\\b"
              + "|DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?%[Is]\\b",
          FLAGS);

  /** Words a column pattern must never take for an unquoted column name. */
  private static final Set<String> KEYWORDS = Set.of("column", "constraint", "if", "only");

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
    if (REJECTED.matcher(statement).matches()) {
      throw unsupported("statement", statement);
    }
    if (STARTS_CREATE_TABLE.matcher(statement).matches()) {
      Matcher create = CREATE_TABLE.matcher(statement);
      if (!create.matches()) {
        throw unsupported("CREATE TABLE form", statement);
      }
      createTable(tableName(create.group(1), statement), create.group(2).strip(), statement);
    } else if (STARTS_ALTER_TABLE.matcher(statement).matches()) {
      Matcher alter = ALTER_TABLE.matcher(statement);
      if (!alter.matches()) {
        throw unsupported("ALTER TABLE form", statement);
      }
      alterTable(
          alter.group(1) != null,
          tableName(alter.group(2), statement),
          alter.group(3).strip(),
          statement);
    } else if (STARTS_DROP_TABLE.matcher(statement).matches()) {
      Matcher drop = DROP_TABLE.matcher(statement);
      if (!drop.matches()) {
        throw unsupported("DROP TABLE form", statement);
      }
      for (String table : splitTopLevel(drop.group(2))) {
        String name = tableName(table.strip(), statement);
        if (columns.remove(name) == null && drop.group(1) == null) {
          throw unknown("table " + name, statement);
        }
        tableFiles.remove(name);
      }
    }
  }

  private void createTable(String table, String rest, String statement) {
    if (Pattern.compile("PARTITION\\s+OF\\b.*", FLAGS).matcher(rest).matches()) {
      return;
    }
    if (!rest.startsWith("(")) {
      throw unsupported("CREATE TABLE form", statement);
    }
    int close = matchingParenthesis(rest);
    String after = rest.substring(close + 1).strip();
    if (!after.isEmpty()
        && !Pattern.compile("PARTITION\\s+BY\\b.*", FLAGS).matcher(after).matches()) {
      throw unsupported("CREATE TABLE clause '" + after + "'", statement);
    }
    if (columns.containsKey(table)) {
      if (Pattern.compile("CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\b.*", FLAGS)
          .matcher(statement)
          .matches()) {
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
      if (Pattern.compile("LIKE\\b.*", FLAGS).matcher(trimmed).matches()) {
        throw unsupported("CREATE TABLE ... (LIKE ...)", statement);
      }
      Matcher column = Pattern.compile(IDENTIFIER + "(?=\\s|$)").matcher(trimmed);
      if (!column.lookingAt()) {
        throw unsupported("column definition '" + trimmed + "'", statement);
      }
      tableColumns.put(columnName(column.group(1), statement), file);
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
      String target = tableName(renameTable.group(1), statement);
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
        tableColumns.putIfAbsent(columnName(add.group(1), statement), file);
      } else if (drop.matches()) {
        String column = columnName(drop.group(2), statement);
        if (tableColumns.remove(column) == null && drop.group(1) == null) {
          throw unknown("column " + table + "." + column, statement);
        }
      } else if (rename.matches()) {
        String source = columnName(rename.group(1), statement);
        if (tableColumns.remove(source) == null) {
          throw unknown("column " + table + "." + source, statement);
        }
        tableColumns.put(columnName(rename.group(2), statement), file);
      } else {
        throw unsupported("ALTER TABLE action '" + action + "'", statement);
      }
    }
  }

  /**
   * A procedural body ({@code DO}, function) holds no table DDL but the partition maintenance of
   * the protocol tables on run-time names; anything else would be invisible to this reader.
   */
  private void checkProceduralBody(String body) {
    Matcher ddl = BODY_TABLE_DDL.matcher(body);
    while (ddl.find()) {
      Matcher allowed = BODY_ALLOWED.matcher(body);
      allowed.region(ddl.start(), body.length());
      if (!allowed.lookingAt()) {
        throw unsupported(
            "table DDL inside a procedural body (only partition maintenance)",
            body.substring(ddl.start()));
      }
    }
  }

  /** A table name without {@code public.}; any other schema is rejected. */
  private String tableName(String raw, String statement) {
    Matcher qualified = QUALIFIED.matcher(raw);
    if (qualified.matches()) {
      if (!name(qualified.group(1)).equals("public")) {
        throw unsupported("table outside the schema of the installation", statement);
      }
      return name(qualified.group(2));
    }
    if (!SIMPLE.matcher(raw).matches()) {
      throw unsupported("table name '" + raw + "'", statement);
    }
    return name(raw);
  }

  private String columnName(String raw, String statement) {
    String column = name(raw);
    if (!raw.startsWith("\"") && KEYWORDS.contains(column)) {
      throw unsupported("column name '" + raw + "'", statement);
    }
    return column;
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
   * SQL split into top-level statements, with comments removed (block comments nest, as in
   * PostgreSQL), string literals emptied and dollar-quoted bodies taken out; the bodies are kept
   * apart for {@link #checkProceduralBody}.
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
          i = skipBlockComment(sql, i);
          current.append(' ');
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

    private static int skipBlockComment(String sql, int start) {
      int depth = 0;
      int i = start;
      while (i < sql.length()) {
        if (sql.startsWith("/*", i)) {
          depth++;
          i += 2;
        } else if (sql.startsWith("*/", i)) {
          i += 2;
          if (--depth == 0) {
            return i;
          }
        } else {
          i++;
        }
      }
      throw new IllegalStateException("unterminated block comment in " + abbreviate(sql));
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
