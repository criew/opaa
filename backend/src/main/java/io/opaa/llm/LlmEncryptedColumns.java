package io.opaa.llm;

import io.opaa.security.CipherBinding;
import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/** The model keys of the llm package, encrypted with the settings key (ADR-0064). */
@Component
public class LlmEncryptedColumns implements EncryptedColumns {

  /** {@code llm_models.api_key_ciphertext}, rows by their UUID. */
  public static final EncryptedColumn CHAT_MODEL_KEY =
      EncryptedColumn.settings("llm_models", "api_key_ciphertext");

  static final EncryptedColumn RERANK_MODEL_KEY =
      EncryptedColumn.settings("rerank_model", "api_key_ciphertext");
  static final EncryptedColumn TRANSCRIPTION_MODEL_KEY =
      EncryptedColumn.settings("transcription_model", "api_key_ciphertext");
  static final EncryptedColumn EMBEDDING_MODEL_KEY =
      EncryptedColumn.settings("embedding_model", "api_key_ciphertext");
  static final EncryptedColumn PREVIOUS_EMBEDDING_MODEL_KEY =
      EncryptedColumn.settings("embedding_model_previous", "api_key_ciphertext");

  /** The binding of the key of the single rerank model row. */
  public static CipherBinding rerankModelKey() {
    return RERANK_MODEL_KEY.row(RerankModelSettings.SINGLETON_ID);
  }

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(
        CHAT_MODEL_KEY,
        RERANK_MODEL_KEY,
        TRANSCRIPTION_MODEL_KEY,
        EMBEDDING_MODEL_KEY,
        PREVIOUS_EMBEDDING_MODEL_KEY);
  }
}
