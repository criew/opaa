package io.opaa.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One person's own marks on one chat (docs/features/chat-list.md, "Persönliche Ordnungsmerkmale im
 * Datenmodell"). They belong to the person, never to the chat: setting one changes no field of
 * {@link Chat}, and no other person can read or set them. Written only through {@link
 * ChatPersonalMarkRepository}'s statements; {@code organization_id} is derived from the chat by a
 * database trigger and therefore not mapped here.
 */
@Entity
@Table(name = "chat_personal_marks")
@IdClass(ChatPersonalMark.Key.class)
public class ChatPersonalMark {

  @Id
  @Column(name = "chat_id", nullable = false, updatable = false)
  private UUID chatId;

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "pinned_at")
  private Instant pinnedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  protected ChatPersonalMark() {}

  public UUID getChatId() {
    return chatId;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getPinnedAt() {
    return pinnedAt;
  }

  /** When the person moved the chat into their chat archive; never set together with a pin. */
  public Instant getArchivedAt() {
    return archivedAt;
  }

  /** Composite primary key: one row per chat and person. */
  public static class Key implements Serializable {
    private UUID chatId;
    private UUID userId;

    protected Key() {}

    public Key(UUID chatId, UUID userId) {
      this.chatId = chatId;
      this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(chatId, key.chatId)
          && Objects.equals(userId, key.userId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(chatId, userId);
    }
  }
}
