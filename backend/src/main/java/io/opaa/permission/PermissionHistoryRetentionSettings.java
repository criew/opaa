package io.opaa.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single, installation-wide maximum retention period of the rights history (ADR-0036,
 * Entscheidung 8): how long a <em>closed</em> history interval is kept after it ended. {@code
 * retentionMonths} is bounded 12..120 by {@code chk_permission_history_retention_months} - the same
 * bounds as {@code chk_audit_retention_settings_months}, because
 * docs/features/security-and-compliance.md puts the history under the same logic as the protocol.
 *
 * <p>This is a second axis next to ADR-0016, not a replacement for it: the history still outlives
 * the deletion of the <em>object</em> it describes (library, group, grant) and the deletion of an
 * account. Only time removes a row.
 *
 * <p>{@code lastCutoff} carries the progress of {@link PermissionHistoryRetentionDeletionService},
 * not a configured value: the latest cutoff any pass has deleted by. It is a <b>high-water mark</b>
 * and only ever moves forward, so after a lengthening of the period it stays ahead of that period's
 * own cutoff - what is deleted does not come back. It is therefore also the boundary of what the
 * Stichtag reconstruction can still answer, which is why {@link
 * PermissionHistoryRetentionService#retentionCutoff()} hands it out without a rights check.
 *
 * <p>Read-only end to end - every field is {@code insertable = false, updatable = false} and there
 * is no setter, so the two write paths of {@link PermissionHistoryRetentionSettingsRepository} stay
 * the only ones: one writes the configured period, the other the run progress. A dirty-checked
 * {@code save} would blur that split by writing both from one call site.
 */
@Entity
@Table(name = "permission_history_retention_settings")
public class PermissionHistoryRetentionSettings {

  /** Always {@code 1} - enforced by {@code chk_permission_history_retention_singleton}. */
  public static final int SINGLETON_ID = 1;

  /** 1 year, mirrored by {@code chk_permission_history_retention_months}. */
  public static final int MIN_RETENTION_MONTHS = 12;

  /** 10 years, mirrored by the same check constraint. */
  public static final int MAX_RETENTION_MONTHS = 120;

  /** 3 years - what a fresh installation is delivered with (ADR-0036, Zahlentabelle). */
  public static final int DEFAULT_RETENTION_MONTHS = 36;

  @Id private Integer id;

  @Column(name = "retention_months", insertable = false, updatable = false)
  private int retentionMonths;

  @Column(name = "last_cutoff", insertable = false, updatable = false)
  private Instant lastCutoff;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected PermissionHistoryRetentionSettings() {}

  public Integer getId() {
    return id;
  }

  public int getRetentionMonths() {
    return retentionMonths;
  }

  public Instant getLastCutoff() {
    return lastCutoff;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
