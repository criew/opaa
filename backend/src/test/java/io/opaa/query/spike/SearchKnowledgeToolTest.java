package io.opaa.query.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.KnowledgeRetrieval;
import io.opaa.query.retrieval.RetrievalExplanation;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;

/**
 * #1789 acceptance criterion: the search scope and metadata filter a tool call actually searches
 * with come from the {@link ToolContext} (the caller's own rights) - never from a value the model
 * could supply as an argument, since {@link SearchKnowledgeTool#searchKnowledge} takes no such
 * argument at all.
 */
class SearchKnowledgeToolTest {

  private final KnowledgeRetrieval knowledgeRetrieval = mock(KnowledgeRetrieval.class);
  private final SearchKnowledgeTool tool = new SearchKnowledgeTool(knowledgeRetrieval);

  @Test
  void searchKnowledgeSearchesTheScopeAndFilterCarriedByTheRunStateInTheToolContext() {
    Set<UUID> callerReadableScope = Set.of(UUID.randomUUID());
    MetadataFilter callerFilter = MetadataFilter.NONE;
    ToolLoopRunState runState = new ToolLoopRunState(callerReadableScope, callerFilter);
    ToolContext toolContext = new ToolContext(Map.of(SearchKnowledgeTool.RUN_STATE_KEY, runState));
    Document chunk =
        Document.builder()
            .text("Passage")
            .metadata(Map.of("file_name", "handbuch.md", "document_id", "doc-1", "chunk_index", 0))
            .build();
    when(knowledgeRetrieval.retrieve(
            eq("Wie melde ich mich an?"),
            eq(List.of()),
            eq(List.of()),
            eq(callerReadableScope),
            eq(callerFilter)))
        .thenReturn(
            new RetrievalPipelineResult(
                List.of(chunk), List.of(), new RetrievalExplanation(List.of()), true));

    String result = tool.searchKnowledge("Wie melde ich mich an?", toolContext);

    assertThat(result).contains("handbuch.md").contains("【source: doc-1#0 | handbuch.md】");
    assertThat(runState.collectedChunks()).containsExactly(chunk);
    assertThat(runState.searchSteps()).containsExactly("Wie melde ich mich an?");
  }

  @Test
  void searchKnowledgeAccumulatesChunksAndStepsAcrossSeveralCallsOfTheSameRunState() {
    ToolLoopRunState runState = new ToolLoopRunState(Set.of(), MetadataFilter.NONE);
    ToolContext toolContext = new ToolContext(Map.of(SearchKnowledgeTool.RUN_STATE_KEY, runState));
    Document firstChunk =
        Document.builder()
            .text("Erste Passage")
            .metadata(Map.of("file_name", "a.md", "document_id", "doc-a", "chunk_index", 0))
            .build();
    Document secondChunk =
        Document.builder()
            .text("Zweite Passage")
            .metadata(Map.of("file_name", "b.md", "document_id", "doc-b", "chunk_index", 0))
            .build();
    when(knowledgeRetrieval.retrieve(
            eq("erste Frage"), eq(List.of()), eq(List.of()), eq(Set.of()), eq(MetadataFilter.NONE)))
        .thenReturn(
            new RetrievalPipelineResult(
                List.of(firstChunk), List.of(), new RetrievalExplanation(List.of()), true));
    when(knowledgeRetrieval.retrieve(
            eq("zweite Frage"),
            eq(List.of()),
            eq(List.of()),
            eq(Set.of()),
            eq(MetadataFilter.NONE)))
        .thenReturn(
            new RetrievalPipelineResult(
                List.of(secondChunk), List.of(), new RetrievalExplanation(List.of()), true));

    tool.searchKnowledge("erste Frage", toolContext);
    tool.searchKnowledge("zweite Frage", toolContext);

    assertThat(runState.collectedChunks()).containsExactly(firstChunk, secondChunk);
    assertThat(runState.searchSteps()).containsExactly("erste Frage", "zweite Frage");
  }
}
