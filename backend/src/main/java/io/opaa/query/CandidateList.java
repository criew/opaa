package io.opaa.query;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * One ranked candidate list inside the pipeline, with the label that says where it came from -
 * {@code "vector search · sub-query 1"} today, a second label per sub-query once the lexical path
 * is added (docs/features/hybrid-retrieval.md, Arbeitspaket 2/3).
 *
 * <p>The label exists for the explanation protocol, not for control flow: no stage may branch on
 * it. It is what lets the diagnosis answer which path a candidate came in through when several
 * lists found the same chunk independently. Like every string in the protocol it is technical and
 * English; the operator-facing German wording is the presenting layer's job, not this record's.
 *
 * @param label origin of this list.
 * @param documents the list's chunks, best first.
 */
public record CandidateList(String label, List<Document> documents) {

  public CandidateList {
    documents = List.copyOf(documents);
  }
}
