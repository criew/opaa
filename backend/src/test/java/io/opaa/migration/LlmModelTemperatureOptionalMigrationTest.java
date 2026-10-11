package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code llm_models.temperature} becomes optional without a default: a stored temperature keeps its
 * value, a row without one is accepted, and the range check still holds for a value that is given.
 */
class LlmModelTemperatureOptionalMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/knowledge/2026-10-11-llm-model-temperature-optional.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void aStoredTemperatureSurvivesAndTheColumnBecomesNullable() throws Exception {
    UUID model = insertModel("0.70");
    assertThat(columnAttribute("is_nullable", "temperature")).isEqualTo("NO");

    applyChangelog(connection, FILE);

    assertThat(columnAttribute("is_nullable", "temperature")).isEqualTo("YES");
    assertThat(stringOf("SELECT temperature::text FROM llm_models WHERE id = " + quoted(model)))
        .isEqualTo("0.70");
  }

  @Test
  void aRowWithoutTemperatureIsStoredWithoutOne() throws Exception {
    applyChangelog(connection, FILE);
    UUID model = UUID.randomUUID();

    execute(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier) VALUES ("
            + quoted(model)
            + ", 'Haiku', 'https://api.example/v1', 'claude-haiku')");

    assertThat(
            longOf(
                "SELECT count(*) FROM llm_models WHERE temperature IS NULL AND id = "
                    + quoted(model)))
        .isEqualTo(1);
  }

  @Test
  void aTemperatureOutsideTheRangeIsStillRejected() throws Exception {
    applyChangelog(connection, FILE);

    assertRejected(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier, temperature)"
            + " VALUES ("
            + quoted(UUID.randomUUID())
            + ", 'X', 'http://x/v1', 'x', 2.5)",
        "chk_llm_models_temperature");
  }

  private UUID insertModel(String temperature) throws Exception {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier, temperature,"
            + " max_tokens) VALUES ("
            + quoted(id)
            + ", 'Bestand', 'http://ollama:11434/v1', 'phi3:mini', "
            + temperature
            + ", 2000)");
    return id;
  }

  private String columnAttribute(String attribute, String column) throws Exception {
    return stringOf(
        "SELECT "
            + attribute
            + " FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = 'llm_models' AND column_name = '"
            + column
            + "'");
  }
}
