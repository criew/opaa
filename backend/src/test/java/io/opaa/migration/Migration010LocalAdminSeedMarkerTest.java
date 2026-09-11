package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 010 in isolation (#1534, ADR-0033 Entscheidung 5): the singleton marker of the
 * bootstrap-administrator seed, the same shape as {@code oidc_provider_seed_marker} and {@code
 * llm_model_seed_marker} - one row with {@code id = 1}, written by the seeder and never by the
 * migration itself, so "never attempted" and "attempted" can be told apart after the account was
 * deleted.
 */
class Migration010LocalAdminSeedMarkerTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/010-create-local-admin-seed-marker.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Test
  void createsAnEmptyMarkerTableTheSeederFillsItself() throws SQLException {
    assertThat(LocalAccountSchemaSupport.count(connection, "local_admin_seed_marker", "TRUE"))
        .isZero();
    assertThat(
            LocalAccountSchemaSupport.columnIsNullable(
                connection, "local_admin_seed_marker", "seeded_at"))
        .isFalse();
  }

  @Test
  void acceptsExactlyTheSingletonRow() throws SQLException {
    assertThatCode(
            () -> execute("INSERT INTO local_admin_seed_marker (id, seeded_at) VALUES (1, now())"))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> execute("INSERT INTO local_admin_seed_marker (id, seeded_at) VALUES (1, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("local_admin_seed_marker_pkey");
    assertThatThrownBy(
            () -> execute("INSERT INTO local_admin_seed_marker (id, seeded_at) VALUES (2, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_admin_seed_marker_singleton");
  }
}
