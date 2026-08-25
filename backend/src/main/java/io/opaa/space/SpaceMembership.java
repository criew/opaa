package io.opaa.space;

import io.opaa.api.types.SpaceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "space_memberships")
public class SpaceMembership {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "space_id", nullable = false)
  private Space space;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private SpaceRole role;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SpaceMembership() {}

  public SpaceMembership(UUID userId, SpaceRole role, UUID organizationId) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.role = role;
    this.organizationId = organizationId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  void assignSpace(Space space) {
    this.space = space;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public Space getSpace() {
    return space;
  }

  public SpaceRole getRole() {
    return role;
  }

  public void setRole(SpaceRole role) {
    this.role = role;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
