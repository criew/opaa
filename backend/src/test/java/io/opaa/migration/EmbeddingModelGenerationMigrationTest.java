package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The generations of an existing installation (ADR-0061, Entscheidung 2): the embedding column and
 * a model row that names it get generation 1; a target column of a running re-embedding gets its
 * own, and the model row the generation of the column whose model and size it names; a model row
 * that names neither gets a fresh one. The sequence continues after the highest number given out,
 * so no later model change reuses one.
 */
class EmbeddingModelGenerationMigrationTest extends AbstractMigrationTest {

  private static final String SCRIPT =
      "db/migration/knowledge/V2026.10.28.1__embedding_model_generation.sql";

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
  void aDatabaseNoInstanceStartedOnStartsTheSequenceAfterTheInitialGeneration()
      throws SQLException {
    applyMigrations(connection, SCRIPT);

    assertThat(nextGeneration()).isEqualTo(2);
  }

  @Test
  void aModelRowNamingTheEmbeddingColumnSharesItsInitialGeneration() throws SQLException {
    insertModel("bge-m3", 1024);
    insertIndex("bge-m3", 1024, null, null);

    applyMigrations(connection, SCRIPT);

    assertThat(single("SELECT generation FROM embedding_model")).isEqualTo(1);
    assertThat(single("SELECT generation FROM embedding_index")).isEqualTo(1);
    assertThat(single("SELECT count(*) FROM embedding_index WHERE target_generation IS NULL"))
        .isOne();
    assertThat(nextGeneration()).isEqualTo(2);
  }

  @Test
  void anInstallationInTheMiddleOfAReembeddingKeepsItsTargetUnderAGenerationOfItsOwn()
      throws SQLException {
    insertModel("bge-m3", 1024);
    insertIndex("nomic-embed-text", 768, "bge-m3", 1024);

    applyMigrations(connection, SCRIPT);

    assertThat(single("SELECT generation FROM embedding_index")).isEqualTo(1);
    assertThat(single("SELECT target_generation FROM embedding_index")).isEqualTo(2);
    assertThat(single("SELECT generation FROM embedding_model"))
        .as("the model row names the target column")
        .isEqualTo(2);
    assertThat(nextGeneration()).isEqualTo(3);
  }

  @Test
  void aModelRowChangedWithoutARestartGetsAGenerationOfItsOwn() throws SQLException {
    insertModel("bge-m3", 1024);
    insertIndex("nomic-embed-text", 768, null, null);

    applyMigrations(connection, SCRIPT);

    assertThat(single("SELECT generation FROM embedding_index")).isEqualTo(1);
    assertThat(single("SELECT generation FROM embedding_model")).isEqualTo(2);
    assertThat(nextGeneration()).isEqualTo(3);
  }

  @Test
  void aModelRowNamingNeitherColumnOfARunningReembeddingGetsAThirdGeneration() throws SQLException {
    insertModel("mxbai-embed-large", 1024);
    insertIndex("nomic-embed-text", 768, "bge-m3", 1024);

    applyMigrations(connection, SCRIPT);

    assertThat(single("SELECT target_generation FROM embedding_index")).isEqualTo(2);
    assertThat(single("SELECT generation FROM embedding_model")).isEqualTo(3);
    assertThat(nextGeneration()).isEqualTo(4);
  }

  private void insertModel(String model, int dimensions) throws SQLException {
    execute(
        "INSERT INTO embedding_model (id, base_url, model_identifier, max_input_tokens,"
            + " dimensions, installed_at) VALUES (1, 'http://ollama:11434/v1', '"
            + model
            + "', 2048, "
            + dimensions
            + ", now())");
  }

  private void insertIndex(String model, int dimensions, String targetModel, Integer target)
      throws SQLException {
    execute(
        "INSERT INTO embedding_index (id, model, dimensions, target_model, target_dimensions,"
            + " updated_at) VALUES (1, '"
            + model
            + "', "
            + dimensions
            + ", "
            + (targetModel == null ? "NULL" : "'" + targetModel + "'")
            + ", "
            + (target == null ? "NULL" : target)
            + ", now())");
  }

  private long nextGeneration() throws SQLException {
    return single("SELECT nextval('embedding_generation_seq')");
  }

  private long single(String sql) throws SQLException {
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
