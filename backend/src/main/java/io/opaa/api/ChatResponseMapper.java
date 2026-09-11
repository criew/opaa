package io.opaa.api;

import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatMessageResponse;
import io.opaa.api.dto.ChatNoteItem;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChunkLocation;
import io.opaa.api.dto.SourceMetadataEntry;
import io.opaa.api.dto.SourceReference;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatConversation;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.chat.ChatSourceMetadataEntry;
import io.opaa.chat.ChatTurn;
import java.util.List;

/**
 * Maps {@link Chat}, {@link ChatConversation}, {@link ChatTurn} and {@link ChatSource} onto their
 * generated response counterparts (ADR-0006: API DTOs are generated from the specification, never
 * hand-written).
 */
final class ChatResponseMapper {

  private ChatResponseMapper() {}

  static ChatSummary toSummaryResponse(Chat chat) {
    return new ChatSummary(
            chat.getId(),
            chat.getSpaceId(),
            chat.getAuthorId(),
            chat.isUseKnowledge(),
            chat.getStatus(),
            chat.getCreatedAt(),
            chat.getUpdatedAt())
        .title(chat.getTitle())
        .referencedLibraryIds(List.copyOf(chat.getReferencedLibraryIds()))
        .metadataFilter(MetadataFilterMapper.toResponse(chat.getMetadataFilter()));
  }

  static List<ChatSummary> toSummaryResponses(List<Chat> chats) {
    return chats.stream().map(ChatResponseMapper::toSummaryResponse).toList();
  }

  static ChatDetail toDetailResponse(ChatConversation conversation) {
    List<ChatMessageResponse> messages =
        conversation.getMessages().stream().map(ChatResponseMapper::toMessageResponse).toList();
    return new ChatDetail(
            conversation.getId(),
            conversation.getSpaceId(),
            conversation.getAuthorId(),
            conversation.getUseKnowledge(),
            conversation.getStatus(),
            messages,
            conversation.getCreatedAt(),
            conversation.getUpdatedAt())
        .noteItems(toNoteItems(conversation.getNoteItems()))
        .title(conversation.getTitle())
        .referencedLibraryIds(conversation.getReferencedLibraryIds())
        .metadataFilter(MetadataFilterMapper.toResponse(conversation.getMetadataFilter()));
  }

  /**
   * The Gesprächsnotiz (#1487). Package-private (not private): reused by {@code
   * QueryResponseMapper}, where {@code null} - no persisted chat - stays {@code null} rather than
   * becoming an empty note.
   */
  static List<ChatNoteItem> toNoteItems(List<ChatNotePoint> points) {
    return points == null ? null : points.stream().map(ChatResponseMapper::toNoteItem).toList();
  }

  private static ChatNoteItem toNoteItem(ChatNotePoint point) {
    return new ChatNoteItem(point.id(), point.text(), point.kind(), point.createdAt());
  }

  private static ChatMessageResponse toMessageResponse(ChatTurn turn) {
    return new ChatMessageResponse(
            turn.getId(), turn.getChatId(), turn.getRole(), turn.getContent(), turn.getCreatedAt())
        .sources(toSourceReferences(turn.getSources()));
  }

  /** Package-private (not private): reused by {@code QueryResponseMapper}. */
  static List<SourceReference> toSourceReferences(List<ChatSource> sources) {
    return sources == null
        ? null
        : sources.stream().map(ChatResponseMapper::toSourceReference).toList();
  }

  static SourceReference toSourceReference(ChatSource source) {
    return new SourceReference(
            source.getFileName(),
            source.getRelevanceScore(),
            source.getMatchCount(),
            source.getCited())
        .indexedAt(source.getIndexedAt())
        .documentId(source.getDocumentId())
        .sourceType(source.getSourceType())
        .sourceUrl(source.getSourceUrl())
        .sourceEntryUrl(source.getSourceEntryUrl())
        .citationValid(source.getCitationValid())
        .chunkLocations(toChunkLocations(source.getChunkLocations()))
        .metadata(toMetadataEntries(source.getMetadata()))
        .metadataFilterMatch(source.getMetadataFilterMatch());
  }

  /** An absent or empty list maps to null - the Beleg has nothing to render either way. */
  private static List<SourceMetadataEntry> toMetadataEntries(
      List<ChatSourceMetadataEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return null;
    }
    return entries.stream()
        .map(
            entry ->
                new SourceMetadataEntry(
                        entry.fieldKey(),
                        entry.label(),
                        entry.value(),
                        entry.displayValue(),
                        entry.origin())
                    .datePrecision(entry.datePrecision())
                    .detailOnly(entry.detailOnly()))
        .toList();
  }

  private static List<ChunkLocation> toChunkLocations(List<ChatSourceLocation> locations) {
    return locations == null
        ? null
        : locations.stream()
            .map(
                location ->
                    new ChunkLocation(location.getChunkIndex()).location(location.getLocation()))
            .toList();
  }
}
