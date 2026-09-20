package io.opaa.space;

import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
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
 * A half-open interval {@code [validFrom, validTo)} recording one period a subject - a person or a
 * group - held a role in a space (#1815, ADR-0036 Entscheidung 8). {@code validTo == null} means
 * the interval is open, i.e. the membership exists right now. Written and closed exclusively by
 * {@link SpaceMembershipHistoryService}.
 *
 * <p>Lives in this package for the same reason {@code io.opaa.library.LibraryVisibilityHistory}
 * lives in its own: a space membership is space state. It shares the interval contract and the
 * {@code PermissionHistoryClock} of {@code io.opaa.permission.PermissionHistoryService}, so the
 * guarantee "successive intervals of the same object have strictly increasing boundaries" holds
 * across all history tables.
 *
 * <p>Zero-length marker rows ({@code validFrom == validTo}) record a removal as an event of its
 * own, carrying its actor - see {@link #terminal} and {@code
 * io.opaa.permission.GroupMembershipHistory#terminal} for why the closed interval alone does not
 * do: its own cause must stay whatever it originally was.
 */
@Entity
@Table(name = "space_membership_history")
public class SpaceMembershipHistory {

  @Id private UUID id;

  @Column(name = "space_id", nullable = false)
  private UUID spaceId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 20)
  private PermissionSubjectType subjectType;

  @Column(name = "subject_user_id")
  private UUID subjectUserId;

  @Column(name = "subject_group_id")
  private UUID subjectGroupId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private SpaceRole role;

  @Column(name = "member_count_at_grant")
  private Integer memberCountAtGrant;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private SpaceMembershipHistoryCause cause;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SpaceMembershipHistory() {}

  private SpaceMembershipHistory(
      SpaceMembership membership,
      SpaceRole role,
      SpaceMembershipHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.spaceId = membership.getSpace().getId();
    this.organizationId = membership.getOrganizationId();
    this.subjectType = membership.getSubjectType();
    this.subjectUserId = membership.getUserId();
    this.subjectGroupId = membership.getGroupId();
    this.role = role;
    this.memberCountAtGrant = membership.getMemberCountAtGrant();
    this.cause = cause;
    this.actorUserId = actorUserId;
    this.validFrom = validFrom;
  }

  /** The state interval a membership enters with {@code cause} at {@code at}. */
  static SpaceMembershipHistory open(
      SpaceMembership membership, SpaceMembershipHistoryCause cause, UUID actorUserId, Instant at) {
    return new SpaceMembershipHistory(membership, membership.getRole(), cause, actorUserId, at);
  }

  /**
   * A zero-length marker interval recording that the membership ended with {@code cause}, carrying
   * the role it last held. Never selected by a reconstruction, which needs {@code validFrom <= asOf
   * < validTo}.
   */
  static SpaceMembershipHistory terminal(
      SpaceMembership membership,
      SpaceRole lastRole,
      SpaceMembershipHistoryCause cause,
      UUID actorUserId,
      Instant at) {
    SpaceMembershipHistory marker =
        new SpaceMembershipHistory(membership, lastRole, cause, actorUserId, at);
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

  public UUID getSpaceId() {
    return spaceId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public PermissionSubjectType getSubjectType() {
    return subjectType;
  }

  public UUID getSubjectUserId() {
    return subjectUserId;
  }

  public UUID getSubjectGroupId() {
    return subjectGroupId;
  }

  public SpaceRole getRole() {
    return role;
  }

  public Integer getMemberCountAtGrant() {
    return memberCountAtGrant;
  }

  public SpaceMembershipHistoryCause getCause() {
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
