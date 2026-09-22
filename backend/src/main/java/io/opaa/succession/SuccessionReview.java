package io.opaa.succession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One Sichtungsvermerk on an entry: "geprüft am …, weiterhin offen, Grund" (#1819, ADR-0036
 * Entscheidung 6). It lifts the highlight of an aged entry for one more period and triggers nothing
 * - no deadline, no escalation, no mail.
 *
 * <p>Who wrote it is readable here and is <b>no</b> evaluation axis: there is no query, no sort and
 * no parameter by the acting person (Personalrat Z7).
 */
@Entity
@Table(name = "succession_reviews")
public class SuccessionReview {

  @Id private UUID id;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "reviewed_at", nullable = false)
  private Instant reviewedAt;

  @Column(name = "reviewed_by_user_id")
  private UUID reviewedByUserId;

  @Column(name = "reason", nullable = false, length = 1000)
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SuccessionReview() {}

  SuccessionReview(
      UUID caseId, UUID organizationId, Instant reviewedAt, UUID reviewedByUserId, String reason) {
    this.id = UUID.randomUUID();
    this.caseId = caseId;
    this.organizationId = organizationId;
    this.reviewedAt = reviewedAt;
    this.reviewedByUserId = reviewedByUserId;
    this.reason = reason;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }

  public UUID getReviewedByUserId() {
    return reviewedByUserId;
  }

  public String getReason() {
    return reason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
