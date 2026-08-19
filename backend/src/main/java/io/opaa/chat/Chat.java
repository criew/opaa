package io.opaa.chat;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A persistent, space-owned chat (#525, docs/features/spaces-and-assets.md#chats). Composition, not
 * association: {@link #spaceId} is set once at construction and never changes - a chat cannot be
 * moved to another space. Visible only to {@link #authorId}; not even a space or system admin may
 * read it while {@link #status} is {@link ChatStatus#PRIVATE} - the only value that currently
 * exists, see that enum's Javadoc.
 */
@Entity
@Table(name = "chats")
public class Chat {

  @Id private UUID id;

  @Column(name = "space_id", nullable = false, updatable = false)
  private UUID spaceId;

  @Column(name = "author_id", nullable = false, updatable = false)
  private UUID authorId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "title", length = 255)
  private String title;

  @Column(name = "use_knowledge", nullable = false)
  private boolean useKnowledge;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ChatStatus status;

  /**
   * Sticky @-references (epic #523 "Entschiedene Semantik"). Used as the search scope when {@link
   * #useKnowledge} is {@code false}; ignored when it is {@code true}. Backed by the
   * chat_library_references join table (migration 030), not an array column, so both foreign keys
   * are enforced at the database level.
   */
  @ElementCollection
  @CollectionTable(name = "chat_library_references", joinColumns = @JoinColumn(name = "chat_id"))
  @Column(name = "library_id")
  private Set<UUID> referencedLibraryIds = new LinkedHashSet<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Chat() {}

  public Chat(
      UUID spaceId,
      UUID authorId,
      UUID organizationId,
      String title,
      boolean useKnowledge,
      Set<UUID> referencedLibraryIds) {
    this.id = UUID.randomUUID();
    this.spaceId = spaceId;
    this.authorId = authorId;
    this.organizationId = organizationId;
    this.title = title;
    this.useKnowledge = useKnowledge;
    this.status = ChatStatus.PRIVATE;
    if (referencedLibraryIds != null) {
      this.referencedLibraryIds = new LinkedHashSet<>(referencedLibraryIds);
    }
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

  /**
   * Applies a PATCH: every parameter is optional and only overwrites the current value when
   * non-null, matching {@code ChatUpdateRequest}'s "omitted field leaves it unchanged" semantics
   * (see the OpenAPI spec).
   */
  public void applyUpdate(
      String newTitle, Boolean newUseKnowledge, Set<UUID> newReferencedLibraryIds) {
    if (newTitle != null) {
      this.title = newTitle;
    }
    if (newUseKnowledge != null) {
      this.useKnowledge = newUseKnowledge;
    }
    if (newReferencedLibraryIds != null) {
      this.referencedLibraryIds.clear();
      this.referencedLibraryIds.addAll(newReferencedLibraryIds);
    }
  }

  /**
   * Sets the title from the first question if none was ever set explicitly - called after the first
   * turn is appended (see {@code ChatService#appendTurn}). A title explicitly set to blank by the
   * author is left alone; only the true "never set" case (still {@code null}) falls back.
   */
  public void deriveTitleFromFirstQuestionIfAbsent(String derivedTitle) {
    if (this.title == null) {
      this.title = derivedTitle;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getSpaceId() {
    return spaceId;
  }

  public UUID getAuthorId() {
    return authorId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getTitle() {
    return title;
  }

  public boolean isUseKnowledge() {
    return useKnowledge;
  }

  public ChatStatus getStatus() {
    return status;
  }

  public Set<UUID> getReferencedLibraryIds() {
    return Collections.unmodifiableSet(referencedLibraryIds);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
