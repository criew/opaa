package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code vector_store} moves from the knowledge baseline to the installation (ADR-0060,
 * Entscheidung 10): on a database no instance started on, the migration drops the baseline's empty
 * table; on an installation that has started - an {@code embedding_index} row - or that holds a
 * chunk, the table and its rows stay. The table the installation creates instead has the baseline's
 * columns and indexes, so old and new installations are the same to the application.
 */
class VectorStoreAtInstallationMigrationTest extends AbstractMigrationTest {

  private static final String SCRIPT =
      "db/migration/knowledge/V2026.10.25.5__contract_vector_store_at_installation.sql";

  private Connection connection;

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @BeforeEach
  void openConnection() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void closeConnection() throws SQLException {
    connection.close();
  }

  @Test
  void dropsTheEmptyTableOfADatabaseNoInstanceStartedOn() throws SQLException {
    applyMigrations(connection, SCRIPT);

    assertThat(vectorTableExists()).isFalse();
  }

  @Test
  void keepsTheTableOfAnInstallationThatHasStarted() throws SQLException {
    execute("INSERT INTO embedding_index (id, dimensions, updated_at) VALUES (1, 1024, now())");

    applyMigrations(connection, SCRIPT);

    assertThat(vectorTableExists()).isTrue();
  }

  @Test
  void keepsTheTableAndItsRowsWhenItHoldsAChunk() throws SQLException {
    execute("INSERT INTO vector_store (content, metadata) VALUES ('Abschnitt', '{}')");

    applyMigrations(connection, SCRIPT);

    assertThat(vectorTableExists()).isTrue();
    assertThat(count("SELECT count(*) FROM vector_store")).isOne();
  }

  @Test
  void theInstallationCreatesTheTableTheBaselineCreated() throws SQLException {
    Map<String, String> baseline = shape();

    applyMigrations(connection, SCRIPT);
    Migrations.createVectorTable(connection, connection.getSchema(), 1024);

    assertThat(shape()).isEqualTo(baseline).isNotEmpty();
  }

  /** Columns with their types and indexes with their definitions. */
  private Map<String, String> shape() throws SQLException {
    Map<String, String> shape = new TreeMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet columns =
            statement.executeQuery(
                "SELECT attname, format_type(atttypid, atttypmod) || ' ' || attnotnull"
                    + " || ' ' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')"
                    + " FROM pg_attribute a LEFT JOIN pg_attrdef d"
                    + " ON d.adrelid = a.attrelid AND d.adnum = a.attnum"
                    + " WHERE attrelid = 'vector_store'::regclass AND attnum > 0"
                    + " AND NOT attisdropped")) {
      while (columns.next()) {
        shape.put("column " + columns.getString(1), columns.getString(2));
      }
    }
    try (Statement statement = connection.createStatement();
        ResultSet indexes =
            statement.executeQuery(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND tablename = 'vector_store'")) {
      while (indexes.next()) {
        shape.put("index " + indexes.getString(1), indexes.getString(2));
      }
    }
    return shape;
  }

  private boolean vectorTableExists() throws SQLException {
    return count("SELECT count(*) FROM pg_class WHERE oid = to_regclass('vector_store')") == 1;
  }

  private long count(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
