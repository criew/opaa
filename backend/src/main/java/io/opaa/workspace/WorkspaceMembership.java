package io.opaa.workspace;

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
@Table(name = "workspace_memberships")
public class WorkspaceMembership {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private WorkspaceRole role;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected WorkspaceMembership() {}

  public WorkspaceMembership(UUID userId, WorkspaceRole role) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.role = role;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  void assignWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public Workspace getWorkspace() {
    return workspace;
  }

  public WorkspaceRole getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
