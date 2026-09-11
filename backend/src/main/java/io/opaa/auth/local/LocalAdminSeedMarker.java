package io.opaa.auth.local;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The permanent record that {@link LocalAdminSeeder} attempted to create the bootstrap
 * administrator (ADR-0033, Entscheidung 5) - the same singleton shape as {@code
 * OidcProviderSeedMarker} and {@code LlmModelSeedMarker}, for the same reason: "does the bootstrap
 * row exist?" would resurrect the account on the next start after it was deleted; only {@code
 * OPAA_LOCAL_ADMIN_RESET=force} does that, deliberately.
 */
@Entity
@Table(name = "local_admin_seed_marker")
public class LocalAdminSeedMarker {

  /** Always {@code 1} - enforced by {@code chk_local_admin_seed_marker_singleton}. */
  public static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "seeded_at", nullable = false)
  private Instant seededAt;

  protected LocalAdminSeedMarker() {}

  public LocalAdminSeedMarker(Instant seededAt) {
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
