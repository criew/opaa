package io.opaa.query.spike;

import io.opaa.indexing.metadata.MetadataFilter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.document.Document;

/**
 * Per-turn state a {@code search_knowledge} tool call reads and writes through the {@link
 * org.springframework.ai.chat.model.ToolContext} (#1789): the search scope and metadata filter come
 * from here, never from a model argument, and every chunk any call returns accumulates here across
 * the whole tool-calling loop of one turn. One instance per {@link
 * SpikeToolLoopQueryHandler#handle} call, never shared between turns or callers.
 */
final class ToolLoopRunState {

  private final Set<UUID> searchScope;
  private final MetadataFilter metadataFilter;
  private final List<Document> collectedChunks = new CopyOnWriteArrayList<>();
  private final List<String> searchSteps = new CopyOnWriteArrayList<>();

  ToolLoopRunState(Set<UUID> searchScope, MetadataFilter metadataFilter) {
    this.searchScope = searchScope;
    this.metadataFilter = metadataFilter;
  }

  Set<UUID> searchScope() {
    return searchScope;
  }

  MetadataFilter metadataFilter() {
    return metadataFilter;
  }

  List<Document> collectedChunks() {
    return collectedChunks;
  }

  List<String> searchSteps() {
    return searchSteps;
  }
}
