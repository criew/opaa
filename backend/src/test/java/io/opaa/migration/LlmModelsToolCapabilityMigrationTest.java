package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The tool capability of the chat models on an existing installation: every model present before
 * reads UNVERIFIED without basis, date or measurement run afterwards, a model the previous version
 * inserts without naming the columns does the same, and the database refuses a statement whose
 * basis does not fit its value.
 */
class LlmModelsToolCapabilityMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/knowledge/V2026.11.09.4__llm_models_tool_capability.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void existingModelsAndModelsOfThePreviousVersionStartUnverified() throws SQLException {
    UUID existing = insertModel(true);

    applyMigrations(connection, SCRIPT);

    assertUnverified(existing);
    UUID insertedByThePreviousVersion = insertModel(false);
    assertUnverified(insertedByThePreviousVersion);
    assertThat(
            stringOf(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name ="
                    + " 'llm_models' AND column_name = 'tool_capability'"))
        .isEqualTo("NO");
  }

  @Test
  void onlyAStatementWhoseBasisFitsItsValueIsStored() throws SQLException {
    UUID model = insertModel(true);
    applyMigrations(connection, SCRIPT);

    assertRejected(
        "UPDATE llm_models SET tool_capability = 'CAPABLE' WHERE id = " + quoted(model),
        "chk_llm_models_tool_capability_statement");
    assertRejected(
        "UPDATE llm_models SET tool_capability = 'CAPABLE', tool_capability_basis = 'MEASURED',"
            + " tool_capability_assessed_at = now() WHERE id = "
            + quoted(model),
        "chk_llm_models_tool_capability_statement");
    assertRejected(
        "UPDATE llm_models SET tool_capability = 'UNVERIFIED', tool_capability_basis = 'MANUAL'"
            + " WHERE id = "
            + quoted(model),
        "chk_llm_models_tool_capability_statement");
    assertRejected(
        "UPDATE llm_models SET tool_capability = 'MAYBE' WHERE id = " + quoted(model),
        "chk_llm_models_tool_capability");

    execute(
        "UPDATE llm_models SET tool_capability = 'NOT_CAPABLE', tool_capability_basis = 'MEASURED',"
            + " tool_capability_assessed_at = now(), tool_capability_measurement_run = 'Lauf 1'"
            + " WHERE id = "
            + quoted(model));
    execute(
        "UPDATE llm_models SET tool_capability = 'CAPABLE', tool_capability_basis = 'MANUAL',"
            + " tool_capability_measurement_run = NULL WHERE id = "
            + quoted(model));
    assertThat(stringOf("SELECT tool_capability FROM llm_models WHERE id = " + quoted(model)))
        .isEqualTo("CAPABLE");
  }

  /** A model as the previous version inserts it, without naming any tool capability column. */
  private UUID insertModel(boolean active) throws SQLException {
    if (active) {
      execute("UPDATE llm_models SET active = false");
    }
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier, active) VALUES ("
            + quoted(id)
            + ", 'Ollama', 'http://ollama:11434/v1', 'qwen2.5:14b', "
            + active
            + ")");
    return id;
  }

  private void assertUnverified(UUID model) throws SQLException {
    String where = " FROM llm_models WHERE id = " + quoted(model);
    assertThat(stringOf("SELECT tool_capability" + where)).isEqualTo("UNVERIFIED");
    assertThat(
            booleanOf(
                "SELECT tool_capability_basis IS NULL AND tool_capability_assessed_at IS NULL"
                    + " AND tool_capability_measurement_run IS NULL"
                    + where))
        .isTrue();
  }
}
