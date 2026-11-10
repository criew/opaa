package io.opaa.test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Searches every table of the current schema for a marker text, for the deletion completeness
 * tests: a value written under a unique marker before a deletion must afterwards stand in no column
 * at all, except where a test lists the column as an accepted exception with its reason.
 *
 * <p>Generic on purpose: the columns come from {@code information_schema}, so a table or column
 * added later is searched without anyone listing it. Text, character, JSON, array and {@code
 * tsvector} columns are compared case-insensitively through their text form, {@code bytea} by its
 * bytes; vectors and other user-defined types carry no text and are skipped.
 */
public final class SentinelScan {

  /** Consonants only: no stemmer of the full-text index shortens such a word. */
  private static final String LETTERS = "bcdfghjklmnpqrstvwxz";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** One column holding the marker, with the number of its rows that do. */
  public record Hit(String table, String column, long rows) {

    /** {@code table.column}, the form the exception lists of the tests use. */
    public String qualifiedColumn() {
      return table + "." + column;
    }
  }

  private SentinelScan() {}

  /**
   * A fresh marker of lower-case consonants ending in {@code q}: unique per call, unchanged by the
   * German and the simple text search configuration, free of {@code LIKE} wildcards.
   */
  public static String newSentinel() {
    StringBuilder sentinel = new StringBuilder("zzsntl");
    for (int i = 0; i < 16; i++) {
      sentinel.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
    }
    return sentinel.append('q').toString();
  }

  /** Every column of every table of the current schema that holds {@code sentinel}. */
  public static List<Hit> find(JdbcTemplate jdbc, String sentinel) {
    List<Map<String, Object>> columns =
        jdbc.queryForList(
            "SELECT c.table_name, c.column_name, c.data_type FROM information_schema.columns c"
                + " JOIN information_schema.tables t ON t.table_schema = c.table_schema"
                + " AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'"
                + " WHERE c.table_schema = current_schema()"
                // a partition is searched through its partitioned parent
                + " AND NOT EXISTS (SELECT 1 FROM pg_class p WHERE p.relname = c.table_name"
                + " AND p.relnamespace = current_schema()::regnamespace AND p.relispartition)"
                + " AND c.data_type IN ('text', 'character varying', 'character', 'json', 'jsonb',"
                + " 'ARRAY', 'tsvector', 'bytea')"
                + " ORDER BY c.table_name, c.column_name");
    List<Hit> hits = new ArrayList<>();
    for (Map<String, Object> column : columns) {
      String table = (String) column.get("table_name");
      String name = (String) column.get("column_name");
      String predicate =
          "bytea".equals(column.get("data_type"))
              ? "position(convert_to(?, 'UTF8') in " + quoted(name) + ") > 0"
              : quoted(name) + "::text ILIKE '%' || ? || '%'";
      Long rows =
          jdbc.queryForObject(
              "SELECT count(*) FROM " + quoted(table) + " WHERE " + predicate,
              Long.class,
              sentinel);
      if (rows != null && rows > 0) {
        hits.add(new Hit(table, name, rows));
      }
    }
    return hits;
  }

  private static String quoted(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
