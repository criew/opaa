package io.opaa.query.spike;

import io.opaa.chat.ChatAttachment;
import io.opaa.metadata.MetadataFilter;
import io.opaa.query.answer.DocumentBlock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.document.Document;

/**
 * Per-turn state a {@code search_knowledge} tool call reads and writes through the {@link
 * org.springframework.ai.chat.model.ToolContext} (#1789): the search scope and metadata filter come
 * from here, never from a model argument, and every chunk any call returns accumulates here across
 * the whole tool-calling loop of one turn. One instance per {@link
 * SpikeToolLoopQueryHandler#handle} call, never shared between turns or callers; its {@link
 * #blockId} fences every tool response of the turn and is the one the system prompt names.
 */
final class ToolLoopRunState {

  private final Set<UUID> searchScope;
  private final MetadataFilter metadataFilter;
  private final String blockId = DocumentBlock.newId();
  private final List<Document> collectedChunks = new CopyOnWriteArrayList<>();
  private final List<String> searchSteps = new CopyOnWriteArrayList<>();
  private final UUID spaceId;
  private final UUID authorId;
  private final List<ChatAttachment> generatedFiles = new CopyOnWriteArrayList<>();
  private final AtomicInteger codeRuns = new AtomicInteger();

  ToolLoopRunState(Set<UUID> searchScope, MetadataFilter metadataFilter) {
    this(searchScope, metadataFilter, null, null);
  }

  /**
   * {@code spaceId} and {@code authorId} are the chat's, {@code null} for a turn outside a chat;
   * {@link RunCodeTool} files what it produces there and nowhere else.
   */
  ToolLoopRunState(
      Set<UUID> searchScope, MetadataFilter metadataFilter, UUID spaceId, UUID authorId) {
    this.searchScope = searchScope;
    this.metadataFilter = metadataFilter;
    this.spaceId = spaceId;
    this.authorId = authorId;
  }

  UUID spaceId() {
    return spaceId;
  }

  UUID authorId() {
    return authorId;
  }

  /** Attachments {@link RunCodeTool} created during this turn, still unbound, in order. */
  List<ChatAttachment> generatedFiles() {
    return generatedFiles;
  }

  AtomicInteger codeRuns() {
    return codeRuns;
  }

  Set<UUID> searchScope() {
    return searchScope;
  }

  MetadataFilter metadataFilter() {
    return metadataFilter;
  }

  String blockId() {
    return blockId;
  }

  List<Document> collectedChunks() {
    return collectedChunks;
  }

  List<String> searchSteps() {
    return searchSteps;
  }
}
