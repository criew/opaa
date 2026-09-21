package io.opaa.group.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A synchronisation plan above the plausibility threshold, waiting for a system administrator to
 * confirm or discard it (#1816, ADR-0036 Entscheidung 3). Before #1816 such a run was simply
 * aborted, which left a legitimate large run - a reorganisation - permanently inapplicable.
 *
 * <p><b>At most one per provider</b> ({@code uk_directory_sync_pending_plans_provider}): a new run
 * replaces the pending plan rather than queueing behind it, otherwise one would pile up every six
 * hours and eventually the oldest would be confirmed.
 *
 * <p>{@link #getFingerprint()} is the print of the plan as it was shown. Confirming recomputes the
 * diff against a fresh snapshot and compares prints; a differing result is presented anew instead
 * of applied, so nobody confirms a diff they never saw. {@link #getReport()} is the shown report as
 * JSON, so the management can display exactly that without reading the directory again.
 */
@Entity
@Table(name = "directory_sync_pending_plans")
public class DirectorySyncPendingPlan {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "provider_id", nullable = false)
  private UUID providerId;

  /** When the run that produced this plan read the directory - the plan's age. */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "fingerprint", nullable = false, length = 64)
  private String fingerprint;

  @Column(name = "changed_fraction", nullable = false)
  private double changedFraction;

  @Column(name = "memberships_removed", nullable = false)
  private int membershipsRemoved;

  /** How many accounts this plan would lock (#1818) - the second number that makes it loud. */
  @Column(name = "accounts_locked", nullable = false)
  private int accountsLocked;

  @Column(name = "report", nullable = false)
  private String report;

  protected DirectorySyncPendingPlan() {}

  public DirectorySyncPendingPlan(
      UUID organizationId,
      UUID providerId,
      Instant createdAt,
      String fingerprint,
      double changedFraction,
      int membershipsRemoved,
      int accountsLocked,
      String report) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.providerId = providerId;
    this.createdAt = createdAt;
    this.fingerprint = fingerprint;
    this.changedFraction = changedFraction;
    this.membershipsRemoved = membershipsRemoved;
    this.accountsLocked = accountsLocked;
    this.report = report;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getProviderId() {
    return providerId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public double getChangedFraction() {
    return changedFraction;
  }

  public int getMembershipsRemoved() {
    return membershipsRemoved;
  }

  public int getAccountsLocked() {
    return accountsLocked;
  }

  public String getReport() {
    return report;
  }
}
