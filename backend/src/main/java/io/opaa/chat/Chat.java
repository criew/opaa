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
 *
 * <p><b>#561 review: {@link #title}/{@link #titleSource}/{@link #updatedAt} are never mutated on an
 * instance loaded long before the write, then written back with a full {@code
 * chatRepository.save(chat)} merge</b> - only {@link #applyUpdate} does that, and only because the
 * load and the save happen in the same short {@code @Transactional} method ({@code
 * ChatService#updateChat}), not across the multi-second gap {@code QueryService#query} leaves
 * between loading a chat and {@code ChatService#appendTurn} eventually writing to it (during which
 * a concurrent {@code PATCH} could rename the chat). Both {@code appendTurn}'s
 * prefix-fallback/{@code touch} and {@code ChatTitleGenerationService}'s LLM-derived title instead
 * go through {@link ChatRepository}'s targeted, atomic {@code @Modifying} update methods, which
 * read and write the current database row in one statement rather than trusting a possibly-stale
 * in-memory snapshot.
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
   * <p><b>#677 (migration 048): chat_library_references also carries organization_id, which this
   * collection deliberately never sets.</b> A BEFORE INSERT trigger, {@code
   * trg_chat_library_references_set_organization}, derives it unconditionally from the row's own
   * {@code chat_id} on every insert - the composite foreign keys {@code
   * fk_chat_library_references_chat_organization}/{@code
   * fk_chat_library_references_library_organization} then reject a chat_id/library_id pair whose
   * organizations differ, at the database level, without the application ever naming an
   * organization_id itself. Widening this {@code @ElementCollection<UUID>} to a {@code
   * Set<Embeddable>} carrying organization_id was considered and rejected (PR #680 review): it
   * would make the column application-settable, and therefore application-misassignable, for a
   * value whose entire point is to be derivable and unforgeable. If this collection ever needs a
   * second attribute for an unrelated reason, keep organization_id out of the Java model and let
   * the trigger keep owning it.
   *
   * <p>{@code EAGER} (#525 review round 2, finding A; kept under #889's read phase/LLM call/write
   * phase pipeline - {@code QueryService#effectiveLibraryScope} still reads this collection on a
   * detached, untransacted {@link Chat} in the read phase, never inside the write phase's
   * transaction): the default {@code LAZY} fetch would throw {@code LazyInitializationException}
   * there. Safe to pay unconditionally - the collection is small and read on essentially every
   * load.
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
