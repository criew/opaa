package io.opaa.query;

import java.util.List;
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
 */
public record RetrievalPipelineResult(
    List<Document> chunks, List<String> searchQueries, RetrievalExplanation explanation) {}
