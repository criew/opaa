package io.opaa.llm;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row of {@code rerank_model} (ADR-0060, Entscheidung 10), written only by {@link
 * ModelRoleAdministration}. Without it the factory default applies. The key is encrypted with
 * {@code SettingsEncryptor} and leaves {@link ModelRoleEndpoints} decrypted only for the client.
 */
@Entity
@Table(name = "rerank_model")
public class RerankModelSettings extends OptionalRoleSettings {

  protected RerankModelSettings() {}

  RerankModelSettings(Instant now) {
    super(now);
  }
}
