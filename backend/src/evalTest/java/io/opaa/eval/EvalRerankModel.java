package io.opaa.eval;

import io.opaa.llm.LlmEncryptedColumns;
import io.opaa.llm.ModelRoleEndpoints;
import io.opaa.llm.RerankModelRole;
import io.opaa.security.SettingsEncryptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The rerank model of a Variantenvergleich: {@code -Dopaa.eval.rerankBaseUrl} and {@code
 * -Dopaa.eval.rerankModel} store it as the switched-on rerank role of the harness database, the way
 * the model administration does (ADR-0060, Entscheidung 10). Without both the role stays at its
 * factory default, off. The key of a hosted reranker comes from {@value
 * #API_KEY_ENVIRONMENT_VARIABLE} only, never from a {@code -D} value, which the process list shows;
 * it is stored encrypted.
 */
final class EvalRerankModel {

  private static final String BASE_URL_PROPERTY = "opaa.eval.rerankBaseUrl";
  private static final String MODEL_PROPERTY = "opaa.eval.rerankModel";
  static final String API_KEY_ENVIRONMENT_VARIABLE = "OPAA_EVAL_RERANK_API_KEY";

  private EvalRerankModel() {}

  /** Stores the requested rerank model and probes it once, so the run starts on its state. */
  static void installIfRequested(
      JdbcTemplate jdbcTemplate,
      SettingsEncryptor settingsEncryptor,
      ModelRoleEndpoints endpoints,
      RerankModelRole role) {
    String baseUrl = System.getProperty(BASE_URL_PROPERTY);
    String model = System.getProperty(MODEL_PROPERTY);
    jdbcTemplate.update("DELETE FROM rerank_model");
    if (baseUrl != null || model != null) {
      if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
        throw new IllegalArgumentException(
            BASE_URL_PROPERTY + " and " + MODEL_PROPERTY + " belong together");
      }
      EvalChatModel.refuseSecretsInAddress(BASE_URL_PROPERTY, baseUrl.strip());
      String apiKey = System.getenv(API_KEY_ENVIRONMENT_VARIABLE);
      String ciphertext =
          apiKey == null || apiKey.isBlank()
              ? null
              : settingsEncryptor.encrypt(apiKey.strip(), LlmEncryptedColumns.rerankModelKey());
      jdbcTemplate.update(
          "INSERT INTO rerank_model (id, enabled, base_url, model_identifier, api_key_ciphertext,"
              + " updated_at) VALUES (1, true, ?, ?, ?, now())",
          baseUrl.strip(),
          model.strip(),
          ciphertext);
    }
    endpoints.invalidateAll();
    role.refresh();
  }
}
