package io.opaa.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person responsible for an internal group (ADR-0036, Entscheidung 4): they maintain its members,
 * its name, its release for use and its protection mark. Always a natural person - a group as a
 * steward would be nesting through the back door - and never a member by virtue of being a steward.
 *
 * <p>Deliberately without a history table: responsibility carries no read access and is therefore
 * meaningless for "who could read what on day X" (ADR-0036, Entscheidungen 4 and 8). Appointment
 * and dismissal are audit events; the membership changes a steward makes stay in {@code
 * group_membership_history} with the steward as the actor.
 *
 * <p>Modelled with a plain {@code groupId} rather than a {@code @ManyToOne} to {@link Group}: the
 * stewards of a group are read and written independently of its memberships, and a second
 * collection on {@link Group} would be fetched along with every group read that only wants members.
 */
@Entity
@Table(name = "group_stewards")
public class GroupSteward {

  @Id private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "appointed_by_user_id", updatable = false)
  private UUID appointedByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected GroupSteward() {}

  public GroupSteward(UUID groupId, UUID userId, UUID organizationId, UUID appointedByUserId) {
    this.id = UUID.randomUUID();
    this.groupId = groupId;
    this.userId = userId;
    this.organizationId = organizationId;
    this.appointedByUserId = appointedByUserId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getAppointedByUserId() {
    return appointedByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
