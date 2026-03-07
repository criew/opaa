package io.opaa.workspace;

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
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workspaces")
public class Workspace {

  @Id private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "description", length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 20)
  private WorkspaceType type;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "workspace", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<WorkspaceMembership> memberships = new ArrayList<>();

  protected Workspace() {}

  public Workspace(String name, String description, WorkspaceType type, UUID ownerId) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.description = description;
    this.type = type;
    this.ownerId = ownerId;
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

  public void addMembership(WorkspaceMembership membership) {
    memberships.add(membership);
    membership.assignWorkspace(this);
  }

  public void removeMembership(WorkspaceMembership membership) {
    memberships.remove(membership);
    membership.assignWorkspace(null);
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

  public WorkspaceType getType() {
    return type;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public List<WorkspaceMembership> getMemberships() {
    return memberships;
  }
}
