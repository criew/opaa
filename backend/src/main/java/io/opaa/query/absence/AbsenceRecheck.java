package io.opaa.query.absence;

import io.opaa.chat.CitationMarker;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.metadata.MetadataFilter;
import io.opaa.observability.QueryMetrics;
import io.opaa.observability.QueryMetrics.AbsenceRecheckStep;
import io.opaa.query.answer.ActiveAnswerStreams;
import io.opaa.query.answer.AnswerReaderGoneException;
import io.opaa.query.answer.AnswerStreamListener;
import io.opaa.query.citation.CitationMarkerRepair;
import io.opaa.query.citation.CitationParser;
import io.opaa.query.citation.CitationValidator;
import io.opaa.retrieval.ChunkGroupingKey;
import io.opaa.retrieval.KnowledgeRetrieval;
import io.opaa.retrieval.context.NeighborChunkExpansion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Checks an answer that claims something is missing from the sources against the question's search
 * scope. A claim of {@link AbsenceClaims} counts when it carries the answer - the answer has no
 * valid citation, or the claim comes before or in its first cited sentence; a caveat after a cited
 * answer does not. Then the {@link QuestionKeyTerms key terms} of the question are searched through
 * {@link KnowledgeRetrieval#retrieveAsGiven} - the same pipeline and permission filter as every
 * search, over the scope and filter of the request and never wider. A hit counts only when its text
 * carries the key terms and the answer had not seen it; those hits, with their neighbours, are what
 * the caller answers once more from. The caller asks this at most once per answer, so the recheck
 * never loops, and keeps the first answer when anything after it fails ({@link #mustPropagate}).
 */
@Component
public class AbsenceRecheck {

  private static final Logger log = LoggerFactory.getLogger(AbsenceRecheck.class);

  /** Stands in for a valid marker: no whitespace or punctuation, so no sentence ends in it. */
  private static final String VALID_CITATION = "\u3010cited\u3011";

  /** Masked markers placed after the end of their sentence, with the space around them. */
  private static final Pattern TRAILING_CITATION =
      Pattern.compile("([.!?:;])[\\s\\p{Z}]*+(?:\u3010cited\u3011[\\s\\p{Z}]*+)++");

  /**
   * What the recheck of one answer did.
   *
   * @param searchQuery the key terms searched, {@code null} when nothing was searched
   * @param hits the hits of that search before any of them was dropped
   * @param passages the new passages to answer once more from, empty to keep the answer
   */
  public record Outcome(String searchQuery, int hits, List<Document> passages) {

    static final Outcome NONE = new Outcome(null, 0, List.of());

    public boolean searched() {
      return searchQuery != null;
    }
  }

  private final KnowledgeRetrieval knowledgeRetrieval;
  private final NeighborChunkExpansion neighborChunkExpansion;
  private final CitationParser citationParser;
  private final CitationValidator citationValidator;
  private final AbsenceRecheckProperties properties;
  private final QueryMetrics metrics;

  public AbsenceRecheck(
      KnowledgeRetrieval knowledgeRetrieval,
      NeighborChunkExpansion neighborChunkExpansion,
      CitationParser citationParser,
      CitationValidator citationValidator,
      AbsenceRecheckProperties properties,
      QueryMetrics metrics) {
    this.knowledgeRetrieval = knowledgeRetrieval;
    this.neighborChunkExpansion = neighborChunkExpansion;
    this.citationParser = citationParser;
    this.citationValidator = citationValidator;
    this.properties = properties;
    this.metrics = metrics;
  }

  /**
   * Rechecks {@code answer} to {@code question}; {@code context} is everything the answer was
   * given. {@link Outcome#NONE} when the switch is off or the answer makes no claim about the
   * question; no passages when the question has no key terms, the scope is empty, the search fails
   * or finds nothing new.
   */
  public Outcome recheck(
      QueryMetrics.Mode mode,
      String question,
      String answer,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter,
      List<Document> context) {
    if (!properties.enabled()) {
      return Outcome.NONE;
    }
    if (!AbsenceClaims.claimsAbsence(answer) || !claimCarriesTheAnswer(answer, context)) {
      return Outcome.NONE;
    }
    QuestionKeyTerms terms = QuestionKeyTerms.of(question);
    metrics.recordAbsenceRecheck(mode, AbsenceRecheckStep.DETECTED);
    if (terms.isEmpty() || searchScope == null || searchScope.isEmpty()) {
      return Outcome.NONE;
    }
    metrics.recordAbsenceRecheck(mode, AbsenceRecheckStep.SEARCHED);
    String query = terms.searchQuery();
    List<Document> hits;
    try {
      hits =
          knowledgeRetrieval
              .retrieveAsGiven(
                  query, searchScope, metadataFilter == null ? MetadataFilter.NONE : metadataFilter)
              .chunks();
    } catch (RuntimeException e) {
      if (mustPropagate(e)) {
        throw e;
      }
      log.warn(
          "Absence recheck search failed, the answer stands: {}", e.getClass().getSimpleName());
      return new Outcome(query, 0, List.of());
    }
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
      return new Outcome(query, hits.size(), List.of());
    }
    List<Document> passages = new ArrayList<>(found);
    for (Document neighbour : neighborChunkExpansion.expand(found, searchScope)) {
      if (seen.add(key(neighbour))) {
        passages.add(neighbour);
      }
    }
    log.debug("Absence claim rechecked: {} new passage(s) for a second answer", passages.size());
    return new Outcome(query, hits.size(), List.copyOf(passages));
  }

  /** The answer of {@code mode} was given once more from the passages of the recheck. */
  public void replaced(QueryMetrics.Mode mode) {
    metrics.recordAbsenceRecheck(mode, AbsenceRecheckStep.REPLACED);
  }

  /**
   * Whether a failure after the first answer must end the turn rather than leave the first answer
   * standing: the reader went away or stopped the run, or the instance shuts down.
   */
  public static boolean mustPropagate(RuntimeException failure) {
    return failure instanceof AnswerReaderGoneException
        || (failure instanceof ServiceUnavailableException unavailable
            && ActiveAnswerStreams.SHUTTING_DOWN.equals(unavailable.getCode()))
        || Thread.currentThread().isInterrupted();
  }

  /**
   * {@code subQueriesByDocument} with the documents of {@code passages} attributed to the
   * sub-queries of {@code searchQueries} (1-based) whose key terms their text names, to all of them
   * when it names none: the recheck searched the key terms of the whole question.
   */
  public static Map<String, Set<Integer>> attributed(
      Map<String, Set<Integer>> subQueriesByDocument,
      List<String> searchQueries,
      List<Document> passages) {
    if (searchQueries.isEmpty() || passages.isEmpty()) {
      return subQueriesByDocument;
    }
    Map<String, Set<Integer>> attributed = new HashMap<>();
    subQueriesByDocument.forEach(
        (document, queries) -> attributed.put(document, new LinkedHashSet<>(queries)));
    for (Document passage : passages) {
      Set<Integer> queries = new LinkedHashSet<>();
      for (int i = 1; i <= searchQueries.size(); i++) {
        if (QuestionKeyTerms.of(searchQueries.get(i - 1)).mentionedIn(passage.getText())) {
          queries.add(i);
        }
      }
      if (queries.isEmpty()) {
        for (int i = 1; i <= searchQueries.size(); i++) {
          queries.add(i);
        }
      }
      attributed
          .computeIfAbsent(ChunkGroupingKey.of(passage), document -> new LinkedHashSet<>())
          .addAll(queries);
    }
    return attributed;
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

  /**
   * Whether the absence claim of {@code answer} is its answer rather than a caveat after it: the
   * answer carries no valid citation, or a claim comes before or in its first sentence with a valid
   * citation. A caveat after a cited answer never counts. Markers are masked before the sentence
   * split, so the colon inside a marker never cuts a sentence, and a marker right after a sentence
   * end belongs to the sentence before it.
   */
  private boolean claimCarriesTheAnswer(String answer, List<Document> context) {
    String repaired = CitationMarkerRepair.repair(answer, context);
    List<CitationValidator.ValidatedCitation> citations =
        citationValidator.validate(citationParser.extractCitations(repaired), context, repaired);
    if (citations.stream().noneMatch(CitationValidator.ValidatedCitation::valid)) {
      return true;
    }
    Matcher marker = CitationMarker.PATTERN.matcher(repaired);
    StringBuilder masked = new StringBuilder();
    int index = 0;
    while (marker.find()) {
      boolean valid = index < citations.size() && citations.get(index).valid();
      marker.appendReplacement(masked, valid ? VALID_CITATION : "");
      index++;
    }
    marker.appendTail(masked);
    String attached = TRAILING_CITATION.matcher(masked).replaceAll(VALID_CITATION + "$1 ");
    for (String sentence : AbsenceClaims.sentences(attached)) {
      if (AbsenceClaims.isClaim(sentence)) {
        return true;
      }
      if (sentence.contains(VALID_CITATION)) {
        return false;
      }
    }
    return false;
  }

  /** One passage once, whatever path found it: its document and chunk index. */
  private static String key(Document passage) {
    return ChunkGroupingKey.of(passage)
        + "#"
        + passage.getMetadata().getOrDefault("chunk_index", "0");
  }
}
