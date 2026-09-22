package io.opaa.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The contact point of a provider group (ADR-0036, Entscheidung 9): a person the system
 * administration names, who is a member of the group and holds <b>no maintenance right</b> over it.
 * The row entitles them to exactly one act - setting and releasing the protection mark, which the
 * administration itself must not touch.
 *
 * <p>Always a natural person, for the same reason {@link GroupSteward} is: a group as a contact
 * point would be nesting through the back door. Deliberately without a history table - the
 * appointment carries no read access; its appointment and dismissal are audit events (ADR-0036,
 * Entscheidungen 4 and 8).
 */
@Entity
@Table(name = "group_contacts")
public class GroupContact {

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

  protected GroupContact() {}

  public GroupContact(UUID groupId, UUID userId, UUID organizationId, UUID appointedByUserId) {
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
