package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ChangelogSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link ChangelogSchema} reads the changelog without a database, so the compliance guard runs in
 * every unit test run. This test holds its reading against the catalog of a database the master
 * changelog migrated: the same tables, partitions counted as their parent, and the same columns.
 */
class ChangelogSchemaParityTest extends AbstractMigrationTest {

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of(MasterChangelog.PATH);
  }

  @Test
  void theChangelogReadWithoutADatabaseHasTheTablesAndColumnsOfAMigratedOne() throws SQLException {
    Map<String, Set<String>> read = new TreeMap<>();
    ChangelogSchema.read()
        .forEach((table, columns) -> read.put(table, new TreeSet<>(columns.columns().keySet())));

    assertThat(read).isEqualTo(catalog());
  }

  private Map<String, Set<String>> catalog() throws SQLException {
    Map<String, Set<String>> catalog = new TreeMap<>();
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT c.relname, a.attname FROM pg_class c"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " JOIN pg_attribute a ON a.attrelid = c.oid"
                    + " WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p')"
                    + " AND NOT c.relispartition AND a.attnum > 0 AND NOT a.attisdropped"
                    + " AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock')")) {
      while (rows.next()) {
        catalog.computeIfAbsent(rows.getString(1), table -> new TreeSet<>()).add(rows.getString(2));
      }
    }
    return catalog;
  }
}
