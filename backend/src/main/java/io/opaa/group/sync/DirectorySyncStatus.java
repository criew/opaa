package io.opaa.group.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * The durable half of "last-known-good" (#237): one row per organization recording the outcome of
 * the most recent synchronisation run, including dry runs and unreachable attempts. Exists so an
 * unreachable directory's state is reported persistently - visible to whoever checks later, not
 * only to whoever happened to trigger the run that discovered it.
 */
@Entity
@Table(
    name = "directory_sync_status",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_directory_sync_status_organization",
            columnNames = "organization_id"))
public class DirectorySyncStatus {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "last_run_at", nullable = false)
  private Instant lastRunAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "last_outcome", nullable = false, length = 30)
  private DirectorySyncOutcome lastOutcome;

  @Column(name = "last_message", length = 2000)
  private String lastMessage;

  @Column(name = "last_changed_fraction")
  private Double lastChangedFraction;

  @Column(name = "last_applied_at")
  private Instant lastAppliedAt;

  protected DirectorySyncStatus() {}

  public DirectorySyncStatus(UUID organizationId) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
  }

  /**
   * Records the outcome of a run. {@code lastAppliedAt} is only advanced when {@code outcome} is
   * {@link DirectorySyncOutcome#APPLIED} - it is specifically the timestamp of the last time rights
   * actually changed, not of the last attempt.
   */
  public void recordRun(
      Instant runAt, DirectorySyncOutcome outcome, String message, double changedFraction) {
    this.lastRunAt = runAt;
    this.lastOutcome = outcome;
    this.lastMessage = message;
    this.lastChangedFraction = changedFraction;
    if (outcome == DirectorySyncOutcome.APPLIED) {
      this.lastAppliedAt = runAt;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public DirectorySyncOutcome getLastOutcome() {
    return lastOutcome;
  }

  public String getLastMessage() {
    return lastMessage;
  }

  public Double getLastChangedFraction() {
    return lastChangedFraction;
  }

  public Instant getLastAppliedAt() {
    return lastAppliedAt;
  }
}
