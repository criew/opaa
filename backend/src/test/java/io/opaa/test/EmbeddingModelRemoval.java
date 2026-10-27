package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.indexing.chunk.EmbeddingIndexReconciler;
import io.opaa.llm.InstalledEmbeddingModel;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts the shared test database into the state of an installation that skipped its embedding model
 * - no model row, no index record, no vector table - and back. Only for classes of the
 * property-variant signature, whose neighbours expect {@code vector_store} to be recreated; {@link
 * #restore} leaves an empty table of {@link FakeEmbeddingModel#DIMENSIONS} and the model row found
 * before, reconciled.
 */
public final class EmbeddingModelRemoval {

  private final JdbcTemplate jdbc;
  private final InstalledEmbeddingModel installedModel;
  private final EmbeddingIndexReconciler reconciler;
  private Map<String, Object> modelRow;

  public EmbeddingModelRemoval(
      JdbcTemplate jdbc,
      InstalledEmbeddingModel installedModel,
      EmbeddingIndexReconciler reconciler) {
    this.jdbc = jdbc;
    this.installedModel = installedModel;
    this.reconciler = reconciler;
  }

  /** Removes model row, index record and vector table; remembers the model row. */
  public void remove() {
    modelRow =
        jdbc.queryForList("SELECT * FROM embedding_model WHERE id = 1").stream()
            .findFirst()
            .orElse(null);
    jdbc.update("DELETE FROM embedding_model");
    jdbc.update("DELETE FROM embedding_index");
    jdbc.execute("DROP TABLE IF EXISTS vector_store");
    installedModel.invalidateAll();
  }

  /** An empty vector table of {@code dimensions}, as a database whose model row went missing. */
  public void createVectorTable(int dimensions) {
    jdbc.execute(
        "CREATE TABLE vector_store (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text,"
            + " metadata json, embedding vector("
            + dimensions
            + "))");
  }

  /** Puts the remembered model row and an empty vector table back and reconciles the index. */
  public void restore() {
    jdbc.update("DELETE FROM embedding_model");
    if (modelRow != null) {
      jdbc.update(
          "INSERT INTO embedding_model (id, base_url, model_identifier, api_key_ciphertext,"
              + " max_input_tokens, dimensions, installed_at, generation) VALUES (1, ?, ?, ?, ?,"
              + " ?, ?, ?)",
          modelRow.get("base_url"),
          modelRow.get("model_identifier"),
          modelRow.get("api_key_ciphertext"),
          modelRow.get("max_input_tokens"),
          modelRow.get("dimensions"),
          modelRow.get("installed_at"),
          modelRow.get("generation"));
    }
    jdbc.execute("DROP TABLE IF EXISTS vector_store");
    createVectorTable(FakeEmbeddingModel.DIMENSIONS);
    jdbc.update("DELETE FROM embedding_index");
    installedModel.invalidateAll();
    reconciler.reconcile();
  }
}
