package io.opaa.chat;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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

  /**
   * Where {@link #title} came from (#557, migration 034) - see {@link TitleSource}'s Javadoc.
   * {@code GENERATED} unless the constructor or {@link #applyUpdate} set it to {@code CUSTOM}
   * because a title was explicitly supplied.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "title_source", nullable = false, length = 20)
  private TitleSource titleSource;

  @Column(name = "use_knowledge", nullable = false)
  private boolean useKnowledge;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ChatStatus status;

  /**
   * Sticky @-references (epic #523 "Entschiedene Semantik"). Used as the search scope when {@link
   * #useKnowledge} is {@code false}; ignored when it is {@code true}. Backed by the
   * chat_library_references join table (migration 032), not an array column, so both foreign keys
   * are enforced at the database level.
   *
   * <p>{@code EAGER} (#525 review round 2, finding A): {@code QueryService#query} deliberately runs
   * with no ambient transaction (see that method's Javadoc), so a {@link Chat} loaded by {@code
   * ChatService#findOwnedChat} is detached by the time {@code QueryService} reads this collection a
   * few lines later - the default {@code LAZY} fetch would throw {@code
   * LazyInitializationException} there instead of a normal, harmless empty/small collection access.
   * {@code EAGER} is safe to pay unconditionally here because the collection is small (a handful of
   * sticky @-references at most) and read on essentially every load of a {@link Chat} anyway (
   * {@code effectiveLibraryScope} needs it for every persisted-chat query).
   */
  @ElementCollection(fetch = FetchType.EAGER)
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
    // #557: a title supplied at creation - even an explicit blank string - is CUSTOM and must
    // never be overwritten by the prefix fallback or LLM-derived title generation, see
    // TitleSource's Javadoc.
    this.titleSource = title != null ? TitleSource.CUSTOM : TitleSource.GENERATED;
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
      // #557: a user-initiated rename is CUSTOM from here on - permanent, see TitleSource's
      // Javadoc, regardless of whether this happens before or after the chat's first answer.
      this.titleSource = TitleSource.CUSTOM;
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

  /**
   * Applies an LLM-derived title (#557, {@code ChatTitleGenerationService}) unless the chat's title
   * is {@link TitleSource#CUSTOM} - a title the user set explicitly, at creation or via a later
   * {@code PATCH}, always wins, even one set in the narrow window between the question that
   * triggered generation being asked and this method eventually being called (title generation runs
   * asynchronously, well after the answer was already returned - see {@code
   * ChatTitleGenerationService}'s Javadoc). Stays {@link TitleSource#GENERATED}: the title is still
   * system-derived, merely a better one than the prefix fallback it replaces.
   *
   * @return true if the title was applied, false if it was rejected (a {@code CUSTOM} title, or a
   *     blank/null {@code generatedTitle} - e.g. the LLM returned nothing usable)
   */
  public boolean applyGeneratedTitle(String generatedTitle) {
    if (this.titleSource == TitleSource.CUSTOM
        || generatedTitle == null
        || generatedTitle.isBlank()) {
      return false;
    }
    this.title = generatedTitle;
    return true;
  }

  /**
   * Forces {@link #updatedAt} to the current time even if no other field changed - {@link
   * #onUpdate} only fires when Hibernate's dirty checking already produces an UPDATE statement for
   * some other reason, which a turn that neither changes the title nor any other field would not
   * (#525 review, finding/nit d: without this, the chat list's "sorted by last use" ordering goes
   * stale for every follow-up question after the first). Called explicitly by {@code
   * ChatService#appendTurn} before saving.
   */
  public void touch() {
    this.updatedAt = Instant.now();
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

  public TitleSource getTitleSource() {
    return titleSource;
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
