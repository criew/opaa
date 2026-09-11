package io.opaa.chat;

import io.opaa.api.types.ChatNoteItemKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One persisted point of a {@link Chat}'s Gesprächsnotiz (#1487,
 * docs/features/conversation-memory.md, "Bauteil 2"): a single sentence condensed from a user
 * message of this chat, never from an answer.
 *
 * <p>{@link #position} is an application-assigned, per-chat ordinal, strictly increasing and never
 * reused - the same shape {@link ChatMessage#getSequence()} has and for the same reason: {@code
 * created_at} alone cannot order two points condensed from the same turn, and the note's cap drops
 * the <em>oldest</em> point, which needs a total order. {@code uk_chat_note_items_chat_position}
 * enforces it in the database too, so two concurrent condensations cannot both claim one position.
 *
 * <p>{@code organization_id} is deliberately absent from this class: a BEFORE INSERT trigger
 * derives it from the row's own {@code chat_id}, exactly as for {@code chat_library_references} -
 * see {@link Chat#getReferencedLibraryIds()}'s Javadoc for why that value must not be
 * application-settable.
 */
@Entity
@Table(name = "chat_note_items")
public class ChatNoteItem {

  @Id private UUID id;

  @Column(name = "chat_id", nullable = false, updatable = false)
  private UUID chatId;

  @Column(name = "position", nullable = false, updatable = false)
  private int position;

  @Column(name = "text", nullable = false, updatable = false, length = 200)
  private String text;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 20)
  private ChatNoteItemKind kind;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ChatNoteItem() {}

  public ChatNoteItem(UUID chatId, int position, String text, ChatNoteItemKind kind) {
    this.id = UUID.randomUUID();
    this.chatId = chatId;
    this.position = position;
    this.text = text;
    this.kind = kind;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getChatId() {
    return chatId;
  }

  public int getPosition() {
    return position;
  }

  public String getText() {
    return text;
  }

  public ChatNoteItemKind getKind() {
    return kind;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** The read view of this row - what leaves the chat package (#860 Teil 4). */
  public ChatNotePoint toPoint() {
    return new ChatNotePoint(id, text, kind, createdAt);
  }
}
