package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 060 in isolation (#756, PR #763 review). {@code
 * llm_model_seed_marker} depends on no other table, so the fixture chain only has to produce a
 * realistic database to apply it into - {@code test-master-through-007.yaml} is the smallest one
 * that does, the same fixture {@code Migration058CreateLlmModelsTest} uses for its own
 * dependency-free table.
 *
 * <p>What this proves that a service-level test cannot: the table starts empty on a fresh migration
 * (no row seeded by the migration itself - {@code io.opaa.llm.LlmModelSeeder} inserts the one row),
 * and the database, not just the service, refuses a second row.
 */
class Migration060CreateLlmModelSeedMarkerTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-007.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/060-create-llm-model-seed-marker.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void startsEmptyOnAFreshMigration() throws Exception {
    try (Statement statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM llm_model_seed_marker")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isZero();
    }
  }

  @Test
  void acceptsExactlyOneRowWithIdOne() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO llm_model_seed_marker (id, seeded_at) VALUES (1, now())");
    }

    try (Statement statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM llm_model_seed_marker")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void refusesASecondRow() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO llm_model_seed_marker (id, seeded_at) VALUES (1, now())");
    }

    assertThatThrownBy(
            () -> execute("INSERT INTO llm_model_seed_marker (id, seeded_at) VALUES (2, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_llm_model_seed_marker_singleton");
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
