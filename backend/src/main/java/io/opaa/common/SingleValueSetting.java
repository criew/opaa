package io.opaa.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One whole number the system administration may set, its factory default in the code (ADR-0060,
 * Entscheidung 1): the singleton table holds its row {@code id = 1} only once a value was set, and
 * removing the row returns to the factory default. Read from the table on every call, without a
 * cache, so a change holds on every instance from the next read on (ADR-0053). The bounds are the
 * caller's and the table's CHECK.
 */
public final class SingleValueSetting {

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

  /** The value the administration set and when the database stamped it. */
  public record Stored(int value, Instant updatedAt) {}

  private final JdbcTemplate jdbc;
  private final String select;
  private final String upsert;
  private final String delete;

  /**
   * @param table the singleton table with the columns {@code id}, {@code column} and {@code
   *     updated_at}
   */
  public SingleValueSetting(JdbcTemplate jdbc, String table, String column) {
    if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches()) {
      throw new IllegalArgumentException("not an SQL identifier: " + table + "." + column);
    }
    this.jdbc = jdbc;
    this.select = "SELECT " + column + ", updated_at FROM " + table + " WHERE id = 1";
    this.upsert =
        "INSERT INTO "
            + table
            + " (id, "
            + column
            + ", updated_at) VALUES (1, ?, clock_timestamp()) ON CONFLICT (id) DO UPDATE SET "
            + column
            + " = EXCLUDED."
            + column
            + ", updated_at = EXCLUDED.updated_at";
    this.delete = "DELETE FROM " + table + " WHERE id = 1";
  }

  /** The value the administration set, empty while the factory default applies. */
  public Optional<Stored> stored() {
    List<Stored> rows =
        jdbc.query(
            select,
            (row, index) ->
                new Stored(row.getInt(1), row.getObject(2, Timestamp.class).toInstant()));
    return rows.stream().findFirst();
  }

  /** Sets {@code value}, creating the row on first use. */
  public void set(int value) {
    jdbc.update(upsert, value);
  }

  /** Removes the value, so that the factory default applies again. */
  public void clear() {
    jdbc.update(delete);
  }
}
