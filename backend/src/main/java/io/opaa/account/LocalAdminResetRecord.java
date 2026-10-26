package io.opaa.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The record of {@code OPAA_LOCAL_ADMIN_RESET=force} (ADR-0033, Entscheidung 5): while {@code
 * forced_reset_at} is set, a further start with the variable resets nothing; the first start
 * without it clears the field. One row, in the table the former seed marker left behind.
 */
@Entity
@Table(name = "local_admin_seed_marker")
public class LocalAdminResetRecord {

  /** Always {@code 1} - enforced by {@code chk_local_admin_seed_marker_singleton}. */
  public static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "seeded_at", nullable = false)
  private Instant createdAt;

  @Column(name = "forced_reset_at")
  private Instant forcedResetAt;

  protected LocalAdminResetRecord() {}

  public LocalAdminResetRecord(Instant createdAt) {
    this.id = SINGLETON_ID;
    this.createdAt = createdAt;
  }

  public Integer getId() {
    return id;
  }

  public boolean forcedResetDone() {
    return forcedResetAt != null;
  }

  public void markForcedReset(Instant at) {
    this.forcedResetAt = at;
  }

  public void clearForcedReset() {
    this.forcedResetAt = null;
  }
}
