package io.opaa.retrieval;

import io.opaa.api.types.CandidateOutcome;
import io.opaa.api.types.RetrievalStageName;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.ai.document.Document;

/**
 * One retrieval run's outcome: the selected chunks in selection order, the search queries the run
 * actually ran, and the explanation protocol of every registered stage.
 *
 * <p>The explanation is always here. Keeping it is the caller's decision - {@code
 * QueryService#query} lets it fall out of scope, the administration's diagnosis reads it - but
 * producing it is not optional, which is what keeps the diagnosis showing what happened rather than
 * a reconstruction of what should have happened.
 *
 * @param chunks the final selection, best first.
 * @param searchQueries the queries the search stages ran, in order - one entry when decomposition
 *     is off, disabled or failed, up to {@link QueryProperties#maxSubQueries} when it succeeded.
 *     Empty when the run halted before any search.
 * @param explanation one entry per registered stage, in execution order.
 * @param searchNeeded {@code false} when the decomposition found nothing to search for in the
 *     message; the run has searched all the same, with the fallback query.
 */
public record RetrievalPipelineResult(
    List<Document> chunks,
    List<String> searchQueries,
    RetrievalExplanation explanation,
    boolean searchNeeded) {

  /**
   * Which sub-queries (1-based, the numbering of {@link #searchQueries}) each document owes its
   * place in the fusion's selection to, keyed by {@link ChunkGroupingKey}. Read off the {@link
   * RetrievalStageName#RANK_FUSION} protocol: a kept candidate counts for every sub-query whose
   * list contributed to its fused score. Empty when the fusion did not run.
   */
  public Map<String, Set<Integer>> subQueriesByDocument() {
    Map<String, Integer> subQueryByLabel = new HashMap<>();
    for (int i = 1; i <= searchQueries.size(); i++) {
      subQueryByLabel.put(RetrievalListLabel.VECTOR_SEARCH.format(i), i);
      subQueryByLabel.put(RetrievalListLabel.FULL_TEXT_SEARCH.format(i), i);
    }
    Map<String, Set<Integer>> result = new HashMap<>();
    for (StageExplanation stage : explanation.stages()) {
      if (stage.stage() != RetrievalStageName.RANK_FUSION) {
        continue;
      }
      for (CandidateVerdict verdict : stage.verdicts()) {
        if (verdict.outcome() != CandidateOutcome.KEPT) {
          continue;
        }
        for (FusionContribution contribution : verdict.contributions()) {
          Integer subQuery = subQueryByLabel.get(contribution.listLabel());
          if (subQuery != null) {
            result.computeIfAbsent(verdict.documentKey(), key -> new TreeSet<>()).add(subQuery);
          }
        }
      }
    }
    return result;
  }
}
