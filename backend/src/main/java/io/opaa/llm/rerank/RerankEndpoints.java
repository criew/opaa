package io.opaa.llm.rerank;

import io.opaa.instance.InvalidationBus;
import io.opaa.llm.LlmEncryptedColumns;
import io.opaa.llm.OptionalRoleEndpoints;
import io.opaa.security.SettingsEncryptor;
import org.springframework.stereotype.Component;

/**
 * The stored rerank endpoint, cached until the trigger on {@code rerank_model} announces a change.
 */
@Component
public class RerankEndpoints extends OptionalRoleEndpoints<RerankEndpoint> {

  /** The topic the trigger on {@code rerank_model} announces. */
  private static final String TOPIC = "rerank-model";

  private final RerankModelSettingsRepository rows;

  public RerankEndpoints(
      RerankModelSettingsRepository rows,
      SettingsEncryptor settingsEncryptor,
      InvalidationBus bus) {
    super(TOPIC, settingsEncryptor, bus);
    this.rows = rows;
  }

  @Override
  protected RerankEndpoint load() {
    return rows.findStored()
        .map(
            row ->
                new RerankEndpoint(
                    row.isEnabled(),
                    row.getBaseUrl(),
                    row.getModelIdentifier(),
                    decrypt(
                        row.getApiKeyCiphertext(), LlmEncryptedColumns.rerankModelKey(), "rerank")))
        .orElse(RerankEndpoint.FACTORY_DEFAULT);
  }
}
