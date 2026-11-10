package io.opaa.query.runlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The retention periods of the step log and the sandbox log: 7 days by default, at most 30, the
 * sandbox log never longer than the step log ({@code chk_agent_sandbox_log_retention_days}). There
 * is no off value: only the period is configurable, not the deletion. Read-only; the periods change
 * only through {@link AgentRunLogRetentionSettingsRepository#updateRetentionDays}.
 */
@Entity
@Table(name = "agent_run_log_retention_settings")
public class AgentRunLogRetentionSettings {

  public static final int SINGLETON_ID = 1;

  /** Bounds mirrored by the table's checks. */
  public static final int MIN_RETENTION_DAYS = 1;

  public static final int MAX_RETENTION_DAYS = 30;

  @Id private Integer id;

  @Column(name = "step_log_retention_days", insertable = false, updatable = false)
  private int stepLogRetentionDays;

  @Column(name = "sandbox_log_retention_days", insertable = false, updatable = false)
  private int sandboxLogRetentionDays;

  @Column(name = "last_run_day", insertable = false, updatable = false)
  private LocalDate lastRunDay;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  protected AgentRunLogRetentionSettings() {}

  public int getStepLogRetentionDays() {
    return stepLogRetentionDays;
  }

  public int getSandboxLogRetentionDays() {
    return sandboxLogRetentionDays;
  }

  public LocalDate getLastRunDay() {
    return lastRunDay;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
