package io.opaa.permission;

import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A half-open interval {@code [validFrom, validTo)} recording one period in which a subject held a
 * {@link Capability} (#1813, ADR-0036 Entscheidung 8). {@code validTo == null} means the subject
 * holds it right now. Written and closed exclusively by {@link PermissionHistoryService}.
 *
 * <p>Deliberately no {@code role} and no {@code expiresAt}: the state of a capability grant is its
 * existence, so an interval says everything there is to say about the period it covers.
 */
@Entity
@Table(name = "capability_grant_history")
public class CapabilityGrantHistory {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "capability", nullable = false, length = 40)
  private Capability capability;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 20)
  private CapabilitySubjectType subjectType;

  @Column(name = "subject_user_id")
  private UUID subjectUserId;

  @Column(name = "subject_group_id")
  private UUID subjectGroupId;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private CapabilityGrantHistoryCause cause;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CapabilityGrantHistory() {}

  private CapabilityGrantHistory(
      CapabilityGrant grant,
      CapabilityGrantHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.organizationId = grant.getOrganizationId();
    this.capability = grant.getCapability();
    this.subjectType = grant.getSubjectType();
    this.subjectUserId = grant.getSubjectUserId();
    this.subjectGroupId = grant.getSubjectGroupId();
    this.cause = cause;
    this.actorUserId = actorUserId;
    this.validFrom = validFrom;
  }

  /** Opens an interval for a capability the subject holds from {@code at} onwards. */
  public static CapabilityGrantHistory open(
      CapabilityGrant grant, CapabilityGrantHistoryCause cause, UUID actorUserId, Instant at) {
    return new CapabilityGrantHistory(grant, cause, actorUserId, at);
  }

  /**
   * A zero-length marker interval ({@code validFrom == validTo == at}) recording that the subject
   * lost the capability, with the actor who withdrew it. It exists next to the closed interval for
   * the same reason {@link GroupMembershipHistory#terminal} does: the closed interval keeps the
   * cause that opened it, and the withdrawal is a separate, actor-bearing event. Never selected by
   * a reconstruction, because no instant satisfies {@code validFrom <= asOf < validTo}.
   */
  public static CapabilityGrantHistory terminal(
      CapabilityGrant grant, CapabilityGrantHistoryCause cause, UUID actorUserId, Instant at) {
    CapabilityGrantHistory marker = new CapabilityGrantHistory(grant, cause, actorUserId, at);
    marker.close(at);
    return marker;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public void close(Instant validTo) {
    this.validTo = validTo;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Capability getCapability() {
    return capability;
  }

  public CapabilitySubjectType getSubjectType() {
    return subjectType;
  }

  public UUID getSubjectUserId() {
    return subjectUserId;
  }

  public UUID getSubjectGroupId() {
    return subjectGroupId;
  }

  public CapabilityGrantHistoryCause getCause() {
    return cause;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getValidTo() {
    return validTo;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
