package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 058 in isolation (#756). {@code llm_models} depends on no other
 * table, so the fixture chain only has to produce a realistic database to apply it into - {@code
 * test-master-through-007.yaml} is the smallest one that does, the same fixture {@code
 * Migration041BrandingSettingsTest} uses for its own dependency-free table.
 *
 * <p>What this proves that a service-level test cannot: the constraints hold against direct SQL,
 * not merely against writes that went through {@code io.opaa.llm.LlmModelService} - in particular
 * that the database itself, not just the service, refuses a second active row.
 */
class Migration058CreateLlmModelsTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-007.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/058-create-llm-models.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsAnEmptyTableWithTheExpectedDefaults() throws Exception {
    insertModel(UUID.randomUUID(), "Testmodell", false);

    try (Statement statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT temperature, max_tokens, active, api_key_ciphertext, created_at, updated_at"
                    + " FROM llm_models")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getBigDecimal("temperature")).isEqualByComparingTo("0.70");
      assertThat(rows.getInt("max_tokens")).isEqualTo(2000);
      assertThat(rows.getBoolean("active")).isFalse();
      assertThat(rows.getString("api_key_ciphertext")).isNull();
      assertThat(rows.getTimestamp("created_at")).isNotNull();
      assertThat(rows.getTimestamp("updated_at")).isNotNull();
      assertThat(rows.next()).as("exactly one row").isFalse();
    }
  }

  @Test
  void allowsAtMostOneActiveModel() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    insertModel(first, "Erstes Modell", true);

    assertThatThrownBy(() -> insertModel(second, "Zweites Modell", true))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_llm_models_single_active");
  }

  @Test
  void allowsAnyNumberOfInactiveModelsAlongsideOneActiveModel() {
    assertThatCode(
            () -> {
              insertModel(UUID.randomUUID(), "Aktiv", true);
              insertModel(UUID.randomUUID(), "Inaktiv 1", false);
              insertModel(UUID.randomUUID(), "Inaktiv 2", false);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsATemperatureOutsideZeroToTwo() throws Exception {
    assertThatThrownBy(() -> insertModelWithTemperature("-0.10"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_llm_models_temperature");
    assertThatThrownBy(() -> insertModelWithTemperature("2.10"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_llm_models_temperature");

    assertThatCode(() -> insertModelWithTemperature("0.00")).doesNotThrowAnyException();
  }

  @Test
  void rejectsANonPositiveMaxTokens() throws Exception {
    assertThatThrownBy(() -> insertModelWithMaxTokens(0))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_llm_models_max_tokens");
    assertThatThrownBy(() -> insertModelWithMaxTokens(-1))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_llm_models_max_tokens");
  }

  private void insertModel(UUID id, String displayName, boolean active) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO llm_models (id, display_name, base_url, model_identifier, active) VALUES ('"
              + id
              + "', '"
              + displayName
              + "', 'http://ollama:11434/v1', 'phi3:mini', "
              + active
              + ")");
    }
  }

  private void insertModelWithTemperature(String temperature) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO llm_models (id, display_name, base_url, model_identifier, temperature)"
              + " VALUES ('"
              + UUID.randomUUID()
              + "', 'Testmodell', 'http://ollama:11434/v1', 'phi3:mini', "
              + temperature
              + ")");
    }
  }

  private void insertModelWithMaxTokens(int maxTokens) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO llm_models (id, display_name, base_url, model_identifier, max_tokens)"
              + " VALUES ('"
              + UUID.randomUUID()
              + "', 'Testmodell', 'http://ollama:11434/v1', 'phi3:mini', "
              + maxTokens
              + ")");
    }
  }
}
