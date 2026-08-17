package io.opaa.group;

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
 * A half-open interval {@code [validFrom, validTo)} recording one period a user was a member of a
 * group (#238, see docs/features/spaces-and-assets.md#nachweisbarkeit-historisierung-von-rechten).
 * {@code validTo == null} means the interval is still open, i.e. the user is a member right now.
 * Written and closed exclusively by {@link io.opaa.library.PermissionHistoryService}, which lives
 * in {@code io.opaa.library} because it reconstructs the readable-library formula that combines
 * this table with {@link io.opaa.library.AssetGrantHistory} and {@code
 * io.opaa.library.LibraryVisibilityHistory} - this entity itself stays in {@code io.opaa.group}
 * next to {@link GroupMembership}, the table it historises.
 */
@Entity
@Table(name = "group_membership_history")
public class GroupMembershipHistory {

  @Id private UUID id;

  @Column(name = "group_id", nullable = false)
  private UUID groupId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private GroupMembershipHistoryCause cause;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected GroupMembershipHistory() {}

  public GroupMembershipHistory(
      UUID groupId,
      UUID organizationId,
      UUID userId,
      GroupMembershipHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.groupId = groupId;
    this.organizationId = organizationId;
    this.userId = userId;
    this.cause = cause;
    this.actorUserId = actorUserId;
    this.validFrom = validFrom;
  }

  /**
   * A zero-length marker interval ({@code validFrom == validTo == at}) recording that the {@code
   * userId}/{@code groupId} membership ended with {@code cause} - see {@code
   * io.opaa.library.AssetGrantHistory#terminal} for why a removal needs its own marker row rather
   * than relying on the closed interval alone: the closed interval's own cause must stay whatever
   * it originally was (ADDED or a directory-sync add), and the removal is a separate, actor-bearing
   * event #238's acceptance criteria require to be recorded.
   */
  public static GroupMembershipHistory terminal(
      UUID groupId,
      UUID organizationId,
      UUID userId,
      GroupMembershipHistoryCause cause,
      UUID actorUserId,
      Instant at) {
    GroupMembershipHistory marker =
        new GroupMembershipHistory(groupId, organizationId, userId, cause, actorUserId, at);
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

  public UUID getGroupId() {
    return groupId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getUserId() {
    return userId;
  }

  public GroupMembershipHistoryCause getCause() {
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
