package io.opaa.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The single, system-wide retention configuration row
 * (docs/features/security-and-compliance.md#aufbewahrung) - a singleton, not one row per
 * organization. {@code retentionMonths} is bounded 12..120 (1..10 years) by the database check
 * constraint {@code chk_audit_retention_settings_months}; {@link
 * AuditRetentionSettingsService#updateRetention} validates the same bound before ever writing, but
 * the database is the binding guarantee.
 *
 * <p>{@code lastCutoff}/{@code lastRunMonth} are written exclusively by the {@code
 * opaa_audit_delete_expired_partitions()} database function - the application account's database
 * grant deliberately excludes both columns.
 *
 * <p><b>This entity is read-only end to end:</b> every field is {@code insertable = false,
 * updatable = false}, and there is no setter. Writing {@code retentionMonths} goes exclusively
 * through {@link AuditRetentionSettingsRepository#updateRetentionMonths}, a native
 * {@code @Modifying} query that touches only {@code retention_months}/{@code updated_at} - the two
 * columns the application account actually has {@code UPDATE} on. A plain {@code
 * repository.save(entity)} would not be safe here: Hibernate's default dirty-checked {@code UPDATE}
 * writes every mapped column, including {@code last_cutoff}/{@code last_run_month} - which the
 * application account cannot write - and would fail with "permission denied for table" against the
 * real, restricted grant.
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

  @Column(name = "retention_months", insertable = false, updatable = false)
  private int retentionMonths;

  @Column(name = "last_cutoff", insertable = false, updatable = false)
  private Instant lastCutoff;

  @Column(name = "last_run_month", insertable = false, updatable = false)
  private LocalDate lastRunMonth;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected AuditRetentionSettings() {}

  public Integer getId() {
    return id;
  }

  public int getRetentionMonths() {
    return retentionMonths;
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
}
