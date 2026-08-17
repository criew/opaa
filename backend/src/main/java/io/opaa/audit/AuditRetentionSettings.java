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
 * account's database grant deliberately excludes both columns (see that migration's comment).
 *
 * <p><b>This entity is read-only end to end</b> (code review of #454, finding 2): every field is
 * {@code insertable = false, updatable = false}, and there is no setter. Writing {@code
 * retentionMonths} goes exclusively through {@link
 * AuditRetentionSettingsRepository#updateRetentionMonths}, a native {@code @Modifying} query that
 * touches only {@code retention_months}/{@code updated_at} - the two columns the application
 * account actually has {@code UPDATE} on (migration 023). A plain {@code repository.save(entity)}
 * would not have been safe here even with the setter this class used to have: Hibernate's default
 * dirty-checked {@code UPDATE} writes every mapped column, including {@code last_cutoff}/{@code
 * last_run_month} - which the application account cannot write - so it would fail with "permission
 * denied for table" the moment {@code save} ran against the real, restricted grant, not merely
 * against a schema-only test double. See {@code Migration023AuditRetentionTest} for both the red
 * reproduction of that failure (the naive multi-column {@code UPDATE}) and the green proof that the
 * narrower statement {@link AuditRetentionSettingsRepository#updateRetentionMonths} issues succeeds
 * against the same restricted role.
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
