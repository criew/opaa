package io.opaa.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain counterpart of the generated {@code ChatDetail} (#860 Teil 4) - a {@link Chat} together
 * with its full, chronologically ordered message history. {@link ChatService#createChat}, {@link
 * ChatService#getChat} and {@link ChatService#updateChat} all return one of these; {@code
 * ChatController} maps it to the generated response via {@code ChatResponseMapper}.
 *
 * <p>Deliberately its own flattened bean rather than a {@code record(Chat chat, List<ChatTurn>
 * messages)} wrapper: mirroring {@code ChatDetail}'s own field/getter shape (including {@code
 * getUseKnowledge()}, not {@link Chat#isUseKnowledge()}) keeps every existing accessor a caller of
 * the old DTO-returning service already used - {@code getSpaceId()}, {@code getTitle()}, etc. -
 * unchanged.
 */
public final class ChatConversation {

  private final UUID id;
  private final UUID spaceId;
  private final UUID authorId;
  private final String title;
  private final boolean useKnowledge;
  private final List<UUID> referencedLibraryIds;
  private final ChatStatus status;
  private final List<ChatTurn> messages;
  private final Instant createdAt;
  private final Instant updatedAt;

  public ChatConversation(Chat chat, List<ChatTurn> messages) {
    this.id = chat.getId();
    this.spaceId = chat.getSpaceId();
    this.authorId = chat.getAuthorId();
    this.title = chat.getTitle();
    this.useKnowledge = chat.isUseKnowledge();
    this.referencedLibraryIds = List.copyOf(chat.getReferencedLibraryIds());
    this.status = chat.getStatus();
    this.messages = messages;
    this.createdAt = chat.getCreatedAt();
    this.updatedAt = chat.getUpdatedAt();
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

  public String getTitle() {
    return title;
  }

  public boolean getUseKnowledge() {
    return useKnowledge;
  }

  public List<UUID> getReferencedLibraryIds() {
    return referencedLibraryIds;
  }

  public ChatStatus getStatus() {
    return status;
  }

  public List<ChatTurn> getMessages() {
    return messages;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
