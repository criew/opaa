package io.opaa.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The single, system-wide retention configuration row (#395,
 * docs/features/security-and-compliance.md#aufbewahrung) - a singleton, not one row per
 * organization; see migration 023's changelog header for why. {@code retentionMonths} is bounded
 * 12..120 (1..10 years) by the database check constraint {@code
 * chk_audit_retention_settings_months}; {@link AuditRetentionSettingsService#updateRetention}
 * validates the same bound before ever writing, but the database is the binding guarantee.
 *
 * <p>{@code lastCutoff}/{@code lastRunMonth} are written exclusively by the {@code
 * opaa_audit_delete_expired_partitions()} database function (migration 023) - the application
 * account's database grant deliberately excludes both columns (see that migration's comment), so
 * there is no setter for either here; this class only ever reads them.
 */
@Entity
@Table(name = "audit_retention_settings")
public class AuditRetentionSettings {

  /**
   * Always {@code 1} - see the class Javadoc; enforced by {@code
   * chk_audit_retention_settings_singleton}.
   */
  public static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "retention_months", nullable = false)
  private int retentionMonths;

  @Column(name = "last_cutoff")
  private Instant lastCutoff;

  @Column(name = "last_run_month")
  private LocalDate lastRunMonth;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AuditRetentionSettings() {}

  public Integer getId() {
    return id;
  }

  public int getRetentionMonths() {
    return retentionMonths;
  }

  public void setRetentionMonths(int retentionMonths) {
    this.retentionMonths = retentionMonths;
  }

  public Instant getLastCutoff() {
    return lastCutoff;
  }

  public LocalDate getLastRunMonth() {
    return lastRunMonth;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
