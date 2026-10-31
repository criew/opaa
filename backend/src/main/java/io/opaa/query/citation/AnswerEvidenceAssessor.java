package io.opaa.query.citation;

import io.opaa.api.types.EvidenceLevel;
import io.opaa.chat.AnswerEvidence;
import io.opaa.llm.AnswerCompleteness;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives an answer's Belegstand from the verdicts of {@link CitationValidator} and the sub-query
 * attribution of the retrieval (docs/handbuch/spaces/suche.md, Abschnitt 7) - deterministic, no
 * score and no model call, so the same answer always gets the same level. A confirmed citation
 * covers every sub-query its document was selected for; a document outside that attribution (no
 * fusion protocol) covers nothing, which only matters when the question was decomposed.
 */
public final class AnswerEvidenceAssessor {

  private AnswerEvidenceAssessor() {}

  /**
   * The Belegstand, or {@code null} when there is nothing to assess: a message without anything to
   * search for and without citations, or an empty answer.
   *
   * @param searchQueries the queries the retrieval ran, empty when nothing was searched
   * @param subQueriesByDocument 1-based sub-query numbers per document id
   */
  public static AnswerEvidence assess(
      List<CitationValidator.ValidatedCitation> citations,
      boolean knowledgeSearched,
      boolean passagesFound,
      boolean searchNeeded,
      List<String> searchQueries,
      Map<String, Set<Integer>> subQueriesByDocument,
      AnswerCompleteness completeness) {
    if (completeness == AnswerCompleteness.EMPTY) {
      return null;
    }
    Set<String> confirmedPassages = new LinkedHashSet<>();
    Set<String> confirmedDocuments = new LinkedHashSet<>();
    Set<String> unconfirmed = new LinkedHashSet<>();
    for (CitationValidator.ValidatedCitation citation : citations) {
      String passage = citation.documentId() + "#" + citation.chunkIndex();
      if (citation.valid()) {
        confirmedPassages.add(passage);
        confirmedDocuments.add(citation.documentId());
      } else {
        unconfirmed.add(passage + "|" + citation.fileName());
      }
    }
    if (!searchNeeded && citations.isEmpty()) {
      return null;
    }

    List<String> uncovered = new ArrayList<>();
    if (searchQueries.size() > 1 && !confirmedDocuments.isEmpty()) {
      Set<Integer> covered = new LinkedHashSet<>();
      confirmedDocuments.forEach(
          id -> covered.addAll(subQueriesByDocument.getOrDefault(id, Set.of())));
      for (int i = 1; i <= searchQueries.size(); i++) {
        if (!covered.contains(i)) {
          uncovered.add(searchQueries.get(i - 1));
        }
      }
    }
    boolean truncated = completeness == AnswerCompleteness.TRUNCATED;

    EvidenceLevel level;
    if (confirmedPassages.isEmpty()) {
      level = EvidenceLevel.UNSUPPORTED;
    } else if (!unconfirmed.isEmpty() || !uncovered.isEmpty() || truncated) {
      level = EvidenceLevel.PARTIALLY_SUPPORTED;
    } else {
      level = EvidenceLevel.SUPPORTED;
    }
    return new AnswerEvidence(
        level,
        confirmedPassages.size(),
        confirmedDocuments.size(),
        unconfirmed.size(),
        uncovered,
        truncated,
        knowledgeSearched,
        passagesFound);
  }
}
