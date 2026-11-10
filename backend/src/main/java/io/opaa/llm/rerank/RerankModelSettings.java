package io.opaa.llm.rerank;

import io.opaa.llm.OptionalRoleSettings;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row of {@code rerank_model} (ADR-0060, Entscheidung 10), written only by {@code
 * ModelRoleAdministration}. Without it the factory default applies. The key is encrypted with
 * {@code SettingsEncryptor} and leaves {@link RerankEndpoints} decrypted only for the client.
 */
@Entity
@Table(name = "rerank_model")
public class RerankModelSettings extends OptionalRoleSettings {

  protected RerankModelSettings() {}

  public RerankModelSettings(Instant now) {
    super(now);
  }
}
