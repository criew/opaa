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

  @Column(name = "is_default", nullable = false)
  private boolean isDefault;

  @Column(name = "archived", nullable = false)
  private boolean archived;

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
      boolean isDefault,
      SpaceVisibility visibility,
      UUID ownerId,
      UUID organizationId) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.description = description;
    this.isDefault = isDefault;
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

  /**
   * Marks the space archived (#543, docs/features/spaces-and-assets.md#einen-space-stilllegen-
   * archivieren-statt-löschen) - idempotent, since re-archiving an already archived space is not an
   * error.
   */
  public void archive() {
    this.archived = true;
  }

  public boolean isArchived() {
    return archived;
  }

  /**
   * Whether this is the space created automatically on the user's first login. Exactly one per user
   * and not deletable; in every other respect an ordinary space. Replaces the former {@code
   * SpaceKind.PERSONAL} - see #333 and
   * docs/features/spaces-and-assets.md#es-gibt-nur-eine-art-von-space.
   */
  public boolean isDefault() {
    return isDefault;
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
