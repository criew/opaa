package io.opaa.connection.log;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The single retention row of the connection log, read-only end to end: the period changes only
 * through {@link ConnectionLogRetentionSettingsRepository#updateRetentionMonths}, the progress only
 * through the deletion function. Bounds mirrored by {@code chk_connection_log_retention_months}.
 */
@Entity
@Table(name = "connection_log_retention_settings")
public class ConnectionLogRetentionSettings {

  static final int SINGLETON_ID = 1;
  public static final int MIN_RETENTION_MONTHS = 6;
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

  protected ConnectionLogRetentionSettings() {}

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
