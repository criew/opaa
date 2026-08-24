package io.opaa.chat;

/**
 * Domain counterpart of the generated {@code ChunkLocation} (#860 Teil 4) - where one retrieved
 * chunk sits within its document (#667). {@code QueryService} builds these while ranking search
 * results; {@link ChatService} persists them as part of a turn's sources ({@link ChatMessage
 * #getSources()}) and reads them back unchanged for {@code GET /chats/{chatId}}. Mutable, no-arg
 * constructor included, mirroring the generated DTO's bean shape so the JSON persisted in {@code
 * chat_messages.sources} before this change deserializes into this class unchanged - the property
 * names below match the DTO's {@code @JsonProperty} names exactly.
 */
public final class ChatSourceLocation {

  private int chunkIndex;
  private String location;

  public ChatSourceLocation() {}

  public ChatSourceLocation(int chunkIndex) {
    this.chunkIndex = chunkIndex;
  }

  public ChatSourceLocation location(String location) {
    this.location = location;
    return this;
  }

  public int getChunkIndex() {
    return chunkIndex;
  }

  public void setChunkIndex(int chunkIndex) {
    this.chunkIndex = chunkIndex;
  }

  public String getLocation() {
    return location;
  }

  public void setLocation(String location) {
    this.location = location;
  }
}
