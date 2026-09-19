package io.opaa.query.spike;

import io.opaa.query.KnowledgeRetrieval;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The one tool of the tool-loop spike (#1789): searches the knowledge base for {@code frage}
 * through the same {@link KnowledgeRetrieval} every caller-facing retrieval goes through. Search
 * scope and metadata filter come exclusively from the {@link ToolLoopRunState} carried in the
 * {@link ToolContext} - never from a model argument - so the model can only choose what to search
 * for, never where; every call's chunks and search step accumulate on that same run state across
 * the whole loop of one turn.
 */
@Component
@ConditionalOnProperty(name = "opaa.spike.tool-loop.enabled", havingValue = "true")
class SearchKnowledgeTool {

  static final String RUN_STATE_KEY = "opaa.spike.toolLoop.runState";

  private final KnowledgeRetrieval knowledgeRetrieval;

  SearchKnowledgeTool(KnowledgeRetrieval knowledgeRetrieval) {
    this.knowledgeRetrieval = knowledgeRetrieval;
  }

  @Tool(
      description =
          "Durchsucht die für diesen Chat freigegebene Wissensbasis nach Textstellen zu einer"
              + " Frage oder einem Suchbegriff. Vor jeder inhaltlichen Antwort aufrufen.")
  String searchKnowledge(String frage, ToolContext toolContext) {
    ToolLoopRunState runState = (ToolLoopRunState) toolContext.getContext().get(RUN_STATE_KEY);
    RetrievalPipelineResult result =
        knowledgeRetrieval.retrieve(
            frage, List.of(), List.of(), runState.searchScope(), runState.metadataFilter());
    runState.searchSteps().add(frage);
    runState.collectedChunks().addAll(result.chunks());
    return SpikeChunkFormatting.formatChunks(result.chunks());
  }
}
