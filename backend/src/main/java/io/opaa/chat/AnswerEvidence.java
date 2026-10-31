package io.opaa.chat;

import io.opaa.api.types.EvidenceLevel;
import java.util.List;

/**
 * Domain counterpart of the generated {@code AnswerEvidence} - the Belegstand of one answer.
 * Persisted as JSON on {@link ChatMessage} with the generated DTO's field names, so a stored answer
 * shows the level it was assessed with, not one recomputed under later rules.
 *
 * @param confirmedPassages distinct document/chunk pairs cited by a confirmed citation
 * @param confirmedDocuments distinct documents among them
 * @param unconfirmedCitations distinct citations the check did not confirm
 * @param uncoveredSubQueries sub-queries no confirmed citation stems from, in search order
 * @param knowledgeSearched whether any knowledge was searched for the answer
 * @param passagesFound whether the search handed the answer any passage
 */
public record AnswerEvidence(
    EvidenceLevel level,
    int confirmedPassages,
    int confirmedDocuments,
    int unconfirmedCitations,
    List<String> uncoveredSubQueries,
    boolean truncated,
    boolean knowledgeSearched,
    boolean passagesFound) {

  public AnswerEvidence {
    uncoveredSubQueries =
        uncoveredSubQueries == null ? List.of() : List.copyOf(uncoveredSubQueries);
  }
}
