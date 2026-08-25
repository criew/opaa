package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain counterpart of the generated {@code ChatMessageResponse} (#860 Teil 4) - one persisted
 * {@link ChatMessage}, with its JSON-serialized {@code sources} column already parsed back into
 * {@link ChatSource}s. Built by {@link ChatService} for {@link ChatConversation#getMessages()};
 * immutable, since a turn is read once it is persisted and never mutated in place afterwards
 * (unlike {@link ChatSource}, which {@code QueryService} still mutates while ranking/merging before
 * a turn is ever built).
 */
public final class ChatTurn {

  private final UUID id;
  private final UUID chatId;
  private final ChatRole role;
  private final String content;
  private final List<ChatSource> sources;
  private final Instant createdAt;

  public ChatTurn(
      UUID id,
      UUID chatId,
      ChatRole role,
      String content,
      List<ChatSource> sources,
      Instant createdAt) {
    this.id = id;
    this.chatId = chatId;
    this.role = role;
    this.content = content;
    this.sources = sources;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getChatId() {
    return chatId;
  }

  public ChatRole getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public List<ChatSource> getSources() {
    return sources;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
