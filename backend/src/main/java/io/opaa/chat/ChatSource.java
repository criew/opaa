package io.opaa.chat;

import io.opaa.api.types.DocumentSourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain counterpart of the generated {@code SourceReference} (#860 Teil 4) - a single cited or
 * retrieved document behind a chat turn's answer. {@code QueryService} builds and merges these
 * while ranking search results ({@code QueryService#mergeSourceReferences} mutates a "preferred"
 * instance in place, hence the mutable setters below, not just the fluent ones); {@link
 * ChatService} persists a turn's sources as JSON on {@link ChatMessage#getSources()} and parses
 * them back unchanged for {@code GET /chats/{chatId}}.
 *
 * <p>Deliberately mirrors the generated DTO's bean shape (including the no-arg constructor and
 * every setter) rather than an immutable record: {@code chat_messages.sources} already holds JSON
 * written before this change under the DTO's {@code @JsonProperty} names, which match this class's
 * field names exactly, so existing persisted rows keep deserializing unchanged.
 */
public final class ChatSource {

  private String fileName;
  private double relevanceScore;
  private int matchCount;
  private boolean cited;
  private Instant indexedAt;
  private UUID documentId;
  private DocumentSourceType sourceType;
  private String sourceUrl;
  private String sourceEntryUrl;
  private Boolean citationValid;
  private List<ChatSourceLocation> chunkLocations;

  public ChatSource() {}

  public ChatSource(String fileName, double relevanceScore, int matchCount, boolean cited) {
    this.fileName = fileName;
    this.relevanceScore = relevanceScore;
    this.matchCount = matchCount;
    this.cited = cited;
  }

  public ChatSource indexedAt(Instant indexedAt) {
    this.indexedAt = indexedAt;
    return this;
  }

  public ChatSource documentId(UUID documentId) {
    this.documentId = documentId;
    return this;
  }

  public ChatSource sourceType(DocumentSourceType sourceType) {
    this.sourceType = sourceType;
    return this;
  }

  public ChatSource sourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
    return this;
  }

  public ChatSource sourceEntryUrl(String sourceEntryUrl) {
    this.sourceEntryUrl = sourceEntryUrl;
    return this;
  }

  public ChatSource citationValid(Boolean citationValid) {
    this.citationValid = citationValid;
    return this;
  }

  public ChatSource chunkLocations(List<ChatSourceLocation> chunkLocations) {
    this.chunkLocations = chunkLocations;
    return this;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public double getRelevanceScore() {
    return relevanceScore;
  }

  public void setRelevanceScore(double relevanceScore) {
    this.relevanceScore = relevanceScore;
  }

  public int getMatchCount() {
    return matchCount;
  }

  public void setMatchCount(int matchCount) {
    this.matchCount = matchCount;
  }

  public boolean getCited() {
    return cited;
  }

  public void setCited(boolean cited) {
    this.cited = cited;
  }

  public Instant getIndexedAt() {
    return indexedAt;
  }

  public void setIndexedAt(Instant indexedAt) {
    this.indexedAt = indexedAt;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public void setDocumentId(UUID documentId) {
    this.documentId = documentId;
  }

  public DocumentSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(DocumentSourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public void setSourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
  }

  public String getSourceEntryUrl() {
    return sourceEntryUrl;
  }

  public void setSourceEntryUrl(String sourceEntryUrl) {
    this.sourceEntryUrl = sourceEntryUrl;
  }

  public Boolean getCitationValid() {
    return citationValid;
  }

  public void setCitationValid(Boolean citationValid) {
    this.citationValid = citationValid;
  }

  public List<ChatSourceLocation> getChunkLocations() {
    return chunkLocations;
  }

  public void setChunkLocations(List<ChatSourceLocation> chunkLocations) {
    this.chunkLocations = chunkLocations;
  }
}
