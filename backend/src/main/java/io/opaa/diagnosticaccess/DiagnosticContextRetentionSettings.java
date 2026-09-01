package io.opaa.diagnosticaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The single retention configuration row of the diagnostic context protocol (Leitplanke (i)): 12
 * months by default, configurable, and bounded 1..24 by {@code
 * chk_diagnostic_context_retention_months}. There is no "aus"-Wert and no on/off flag anywhere in
 * this package - the deletion itself is not switchable, only its period is.
 *
 * <p>Read-only end to end, exactly like {@code AuditRetentionSettings}: {@code lastCutoff}/{@code
 * lastRunMonth} are written only by the database function, and the application account has no
 * {@code UPDATE} privilege on them, so a dirty-checked Hibernate update would fail against the real
 * grant. Changing the period goes through {@link
 * DiagnosticContextRetentionSettingsRepository#updateRetentionMonths}.
 */
@Entity
@Table(name = "diagnostic_context_retention_settings")
public class DiagnosticContextRetentionSettings {

  public static final int SINGLETON_ID = 1;

  /** Bounds mirrored by {@code chk_diagnostic_context_retention_months}. */
  public static final int MIN_RETENTION_MONTHS = 1;

  public static final int MAX_RETENTION_MONTHS = 24;

  @Id private Integer id;

  @Column(name = "retention_months", insertable = false, updatable = false)
  private int retentionMonths;

  @Column(name = "last_cutoff", insertable = false, updatable = false)
  private Instant lastCutoff;

  @Column(name = "last_run_month", insertable = false, updatable = false)
  private LocalDate lastRunMonth;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected DiagnosticContextRetentionSettings() {}

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
