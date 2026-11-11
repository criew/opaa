package io.opaa.query.toolcheck;

import io.opaa.api.types.ToolCheckStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One run of the tool-calling case base against a chat model entry. It records the combination it
 * measured ({@link #baseUrl}, {@link #modelIdentifier}), so a result is never mistaken for one of a
 * later address. Progress and the end are written by {@link ToolCheckRepository}'s conditional
 * updates, never by saving this entity, so a check marked aborted stays aborted.
 */
@Entity
@Table(name = "llm_tool_checks")
public class ToolCheck {

  @Id private UUID id;

  @Column(name = "model_id", nullable = false, updatable = false)
  private UUID modelId;

  @Column(name = "base_url", nullable = false, updatable = false)
  private String baseUrl;

  @Column(name = "model_identifier", nullable = false, updatable = false)
  private String modelIdentifier;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private ToolCheckStatus status;

  @Column(name = "repetitions", nullable = false, updatable = false)
  private int repetitions;

  @Column(name = "completed_runs", nullable = false)
  private int completedRuns;

  @Column(name = "total_runs", nullable = false, updatable = false)
  private int totalRuns;

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "heartbeat_at", nullable = false)
  private Instant heartbeatAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "run_id")
  private String runId;

  @Column(name = "failure")
  private String failure;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "report", columnDefinition = "jsonb")
  private String report;

  @Column(name = "report_markdown")
  private String reportMarkdown;

  protected ToolCheck() {}

  ToolCheck(
      UUID modelId,
      String baseUrl,
      String modelIdentifier,
      int repetitions,
      int totalRuns,
      Instant startedAt) {
    this.id = UUID.randomUUID();
    this.modelId = modelId;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.status = ToolCheckStatus.RUNNING;
    this.repetitions = repetitions;
    this.totalRuns = totalRuns;
    this.startedAt = startedAt;
    this.heartbeatAt = startedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getModelId() {
    return modelId;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getModelIdentifier() {
    return modelIdentifier;
  }

  public ToolCheckStatus getStatus() {
    return status;
  }

  public int getRepetitions() {
    return repetitions;
  }

  public int getCompletedRuns() {
    return completedRuns;
  }

  public int getTotalRuns() {
    return totalRuns;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getHeartbeatAt() {
    return heartbeatAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public String getRunId() {
    return runId;
  }

  public String getFailure() {
    return failure;
  }

  /** The {@link ToolCallingReport} as JSON; {@code null} until completed. */
  public String getReport() {
    return report;
  }

  public String getReportMarkdown() {
    return reportMarkdown;
  }
}
