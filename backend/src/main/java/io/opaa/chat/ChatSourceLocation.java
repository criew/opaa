package io.opaa.chat;

import java.util.Map;

/**
 * Domain counterpart of the generated {@code ChunkLocation} - where one retrieved chunk sits within
 * its document. {@code ChatSourceAssembler} builds these while ranking search results; {@link
 * ChatService} persists them as part of a turn's sources ({@link ChatMessage#getSources()}) and
 * reads them back unchanged for {@code GET /chats/{chatId}}. Mutable, no-arg constructor included,
 * mirroring the generated DTO's bean shape: the JSON in {@code chat_messages.sources} uses the
 * DTO's {@code @JsonProperty} names, which the properties below match exactly.
 */
public final class ChatSourceLocation {

  private int chunkIndex;
  private String location;

  /**
   * The cited chunk's anchor as stored on the chunk ({@code ChunkAnchor#toMetadata}), kept as a
   * snapshot of the moment of the answer: a later re-index may renumber the chunks, the anchor
   * still names the passage. Null for a source assembled before anchors existed.
   */
  private Map<String, Object> anchor;

  public ChatSourceLocation() {}

  public ChatSourceLocation(int chunkIndex) {
    this.chunkIndex = chunkIndex;
  }

  public ChatSourceLocation location(String location) {
    this.location = location;
    return this;
  }

  public ChatSourceLocation anchor(Map<String, Object> anchor) {
    this.anchor = anchor;
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

  public Map<String, Object> getAnchor() {
    return anchor;
  }

  public void setAnchor(Map<String, Object> anchor) {
    this.anchor = anchor;
  }
}
