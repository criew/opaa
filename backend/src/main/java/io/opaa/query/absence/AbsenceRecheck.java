package io.opaa.query.absence;

import io.opaa.metadata.MetadataFilter;
import io.opaa.observability.QueryMetrics;
import io.opaa.observability.QueryMetrics.AbsenceRecheckStep;
import io.opaa.query.answer.AnswerStreamListener;
import io.opaa.retrieval.ChunkGroupingKey;
import io.opaa.retrieval.KnowledgeRetrieval;
import io.opaa.retrieval.context.NeighborChunkExpansion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Checks an answer that claims something is missing from the sources against the question's search
 * scope. When {@link AbsenceClaims} recognises such a claim, the {@link QuestionKeyTerms} of the
 * question are searched through {@link KnowledgeRetrieval#retrieveAsGiven} - the same pipeline and
 * permission filter as every search, over the scope and filter of the request and never wider. A
 * hit counts only when its text carries the key terms and the answer had not seen it; those hits,
 * with their neighbours, are what the caller answers once more from. The caller asks this at most
 * once per answer, so the recheck never loops.
 */
@Component
public class AbsenceRecheck {

  private static final Logger log = LoggerFactory.getLogger(AbsenceRecheck.class);

  private final KnowledgeRetrieval knowledgeRetrieval;
  private final NeighborChunkExpansion neighborChunkExpansion;
  private final AbsenceRecheckProperties properties;
  private final QueryMetrics metrics;

  public AbsenceRecheck(
      KnowledgeRetrieval knowledgeRetrieval,
      NeighborChunkExpansion neighborChunkExpansion,
      AbsenceRecheckProperties properties,
      QueryMetrics metrics) {
    this.knowledgeRetrieval = knowledgeRetrieval;
    this.neighborChunkExpansion = neighborChunkExpansion;
    this.properties = properties;
    this.metrics = metrics;
  }

  /**
   * The passages to answer {@code question} once more from, because {@code answer} claims an
   * absence that the search scope may contradict; empty when the switch is off, the answer claims
   * nothing missing, the question has no key terms, the scope is empty or the search found nothing
   * new. {@code context} is everything the answer was given.
   */
  public List<Document> additionalPassages(
      QueryMetrics.Mode mode,
      String question,
      String answer,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter,
      List<Document> context) {
    if (!properties.enabled() || !AbsenceClaims.claimsAbsence(answer)) {
      return List.of();
    }
    metrics.recordAbsenceRecheck(mode, AbsenceRecheckStep.DETECTED);
    QuestionKeyTerms terms = QuestionKeyTerms.of(question);
    if (terms.isEmpty() || searchScope == null || searchScope.isEmpty()) {
      return List.of();
    }
    metrics.recordAbsenceRecheck(mode, AbsenceRecheckStep.SEARCHED);
    List<Document> hits =
        knowledgeRetrieval
            .retrieveAsGiven(
                terms.searchQuery(),
                searchScope,
                metadataFilter == null ? MetadataFilter.NONE : metadataFilter)
            .chunks();
    Set<String> seen = new HashSet<>();
    context.forEach(passage -> seen.add(key(passage)));
    List<Document> found = new ArrayList<>();
    for (Document hit : hits) {
      if (found.size() >= properties.maxPassages()) {
        break;
      }
      if (terms.matches(hit.getText()) && seen.add(key(hit))) {
        found.add(hit);
      }
    }
    if (found.isEmpty()) {
      log.debug("Absence claim rechecked: {} hit(s), none new with the key terms", hits.size());
      return List.of();
    }
    List<Document> passages = new ArrayList<>(found);
    for (Document neighbour : neighborChunkExpansion.expand(found, searchScope)) {
      if (seen.add(key(neighbour))) {
        passages.add(neighbour);
      }
    }
    log.debug("Absence claim rechecked: {} new passage(s) for a second answer", passages.size());
    return List.copyOf(passages);
  }

  /** The answer of {@code mode} was given once more from the passages of the recheck. */
  public void replaced(QueryMetrics.Mode mode) {
    metrics.recordAbsenceRecheck(mode, AbsenceRecheckStep.REPLACED);
  }

  /**
   * {@code listener} for the second answer of a streamed turn: its first text replaces what the
   * reader was shown of the first answer, everything else passes through unchanged.
   */
  public static AnswerStreamListener replacing(AnswerStreamListener listener) {
    if (listener == null) {
      return null;
    }
    return new AnswerStreamListener() {
      private boolean replaced;

      @Override
      public void started() throws IOException {
        listener.started();
      }

      @Override
      public void provisional(String text, boolean replace) throws IOException {
        listener.provisional(text, replace || !replaced);
        replaced = true;
      }

      @Override
      public void idle() throws IOException {
        listener.idle();
      }

      @Override
      public void ping() throws IOException {
        listener.ping();
      }

      @Override
      public void beforeKeeping() throws IOException {
        listener.beforeKeeping();
      }
    };
  }

  /** One passage once, whatever path found it: its document and chunk index. */
  private static String key(Document passage) {
    return ChunkGroupingKey.of(passage)
        + "#"
        + passage.getMetadata().getOrDefault("chunk_index", "0");
  }
}
