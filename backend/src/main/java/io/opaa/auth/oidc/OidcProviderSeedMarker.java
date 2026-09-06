package io.opaa.auth.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The permanent record that {@link OidcProviderSeeder} attempted the one-time takeover of the
 * {@code OPAA_OIDC_*} configuration (#1329, ADR-0025 Entscheidung 3) - the same singleton shape as
 * {@code LlmModelSeedMarker}, for the same reason: "is the table empty?" would resurrect a stale
 * environment configuration the moment a Systemverwaltung deletes every provider.
 *
 * <p><b>Ablaufdatum:</b> einmalige Übernahme für Bestandsinstallationen, Kandidat zur Entfernung ab
 * v1.0.
 */
@Entity
@Table(name = "oidc_provider_seed_marker")
public class OidcProviderSeedMarker {

  /** Always {@code 1} - enforced by {@code chk_oidc_provider_seed_marker_singleton}. */
  public static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "seeded_at", nullable = false)
  private Instant seededAt;

  protected OidcProviderSeedMarker() {}

  public OidcProviderSeedMarker(Instant seededAt) {
    this.id = SINGLETON_ID;
    this.seededAt = seededAt;
  }

  public Integer getId() {
    return id;
  }

  public Instant getSeededAt() {
    return seededAt;
  }
}
