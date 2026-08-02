package io.opaa.space;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "spaces")
public class Space {

  @Id private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "description", length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20)
  private SpaceKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 20)
  private SpaceVisibility visibility;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "space", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<SpaceMembership> memberships = new ArrayList<>();

  protected Space() {}

  public Space(
      String name,
      String description,
      SpaceKind kind,
      SpaceVisibility visibility,
      UUID ownerId,
      UUID organizationId) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.description = description;
    this.kind = kind;
    this.visibility = visibility;
    this.ownerId = ownerId;
    this.organizationId = organizationId;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public void addMembership(SpaceMembership membership) {
    memberships.add(membership);
    membership.assignSpace(this);
  }

  public void removeMembership(SpaceMembership membership) {
    memberships.remove(membership);
    membership.assignSpace(null);
  }

  public void updateDetails(String name, String description, SpaceVisibility visibility) {
    this.name = name;
    this.description = description;
    if (visibility != null) {
      this.visibility = visibility;
    }
  }

  public void transferOwnershipTo(UUID newOwnerId) {
    this.ownerId = newOwnerId;
  }

  public boolean isPersonal() {
    return this.kind == SpaceKind.PERSONAL;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public SpaceKind getKind() {
    return kind;
  }

  public SpaceVisibility getVisibility() {
    return visibility;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public List<SpaceMembership> getMemberships() {
    return Collections.unmodifiableList(memberships);
  }
}
