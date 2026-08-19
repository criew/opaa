package io.opaa.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One persisted question/answer turn of a {@link Chat} (#525). The source of truth for conversation
 * memory - see {@code io.opaa.query.QueryService} - replacing the purely in-memory, TTL-evicted
 * {@code CaffeineChatMemoryRepository} for chats that have a persisted chat id; that cache may
 * remain in front of it as a pure read optimisation.
 *
 * <p>{@link #sources} is stored as a raw JSON string (the {@code chat_messages.sources} column is
 * {@code json}), shaped like {@code QueryResponse.sources} ({@code SourceReference}) - it is
 * (de)serialized by {@code ChatService}, not mapped field by field here, so this entity does not
 * need a dependency on the generated API DTOs.
 *
 * <p>{@link #sequence} is an application-assigned, per-chat ordinal (0, 1, 2, ...) - {@code
 * created_at} alone is not a reliable ordering for two messages of the same turn written moments
 * apart (#525 review, finding/nit c), so every read orders by {@code (chat_id, sequence)}, never by
 * {@code created_at} alone. {@code uk_chat_messages_chat_sequence} (migration 032) enforces
 * uniqueness at the database level too.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

  @Id private UUID id;

  @Column(name = "chat_id", nullable = false, updatable = false)
  private UUID chatId;

  @Column(name = "sequence", nullable = false, updatable = false)
  private int sequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20, updatable = false)
  private ChatRole role;

  @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
  private String content;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sources", updatable = false, columnDefinition = "json")
  private String sources;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ChatMessage() {}

  public ChatMessage(UUID chatId, int sequence, ChatRole role, String content, String sources) {
    this.id = UUID.randomUUID();
    this.chatId = chatId;
    this.sequence = sequence;
    this.role = role;
    this.content = content;
    this.sources = sources;
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

  public int getSequence() {
    return sequence;
  }

  public ChatRole getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public String getSources() {
    return sources;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
