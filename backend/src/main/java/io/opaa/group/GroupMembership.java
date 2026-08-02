package io.opaa.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_memberships")
public class GroupMembership {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "group_id", nullable = false)
  private Group group;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected GroupMembership() {}

  public GroupMembership(UUID userId, UUID organizationId) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.organizationId = organizationId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  void assignGroup(Group group) {
    this.group = group;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public Group getGroup() {
    return group;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
