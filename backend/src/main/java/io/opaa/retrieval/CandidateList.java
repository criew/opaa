package io.opaa.retrieval;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * One ranked candidate list inside the pipeline, with the label that says where it came from - one
 * per search path and search query, e.g. {@code "vector search · sub-query 1"}.
 *
 * <p>The label exists for the explanation protocol, not for control flow: no stage may branch on
 * it. It is what lets the diagnosis answer which path a candidate came in through when several
 * lists found the same chunk independently. Like every string in the protocol it is technical and
 * English; the operator-facing German wording is the presenting layer's job. What a stage may
 * branch on is {@code searchPath} and {@code subQuery}.
 *
 * @param label origin of this list.
 * @param documents the list's chunks, best first.
 * @param searchPath the search that produced the list; {@code null} for a list a later stage
 *     derived (the fused list and everything after it).
 * @param subQuery the 1-based search query the list belongs to; {@code 0} exactly when {@code
 *     searchPath} is {@code null}.
 */
public record CandidateList(
    String label, List<Document> documents, SearchPath searchPath, int subQuery) {

  public CandidateList {
    documents = List.copyOf(documents);
    if ((searchPath == null) != (subQuery == 0) || subQuery < 0) {
      throw new IllegalArgumentException(
          "a searched list names its path and a sub-query >= 1, a derived list neither: path="
              + searchPath
              + ", subQuery="
              + subQuery);
    }
  }

  /** The same list - label, path and sub-query - with other documents. */
  public CandidateList withDocuments(List<Document> otherDocuments) {
    return new CandidateList(label, otherDocuments, searchPath, subQuery);
  }

  /** A list a stage after the searches derived. */
  public CandidateList(String label, List<Document> documents) {
    this(label, documents, null, 0);
  }
}
