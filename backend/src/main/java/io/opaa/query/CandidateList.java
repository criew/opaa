package io.opaa.query;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * One ranked candidate list inside the pipeline, with the label that says where it came from - one
 * per search path and search query, e.g. {@code "vector search · sub-query 1"}.
 *
 * <p>The label exists for the explanation protocol, not for control flow: no stage may branch on
 * it. It is what lets the diagnosis answer which path a candidate came in through when several
 * lists found the same chunk independently. Like every string in the protocol it is technical and
 * English; the operator-facing German wording is the presenting layer's job.
 *
 * @param label origin of this list.
 * @param documents the list's chunks, best first.
 */
public record CandidateList(String label, List<Document> documents) {

  public CandidateList {
    documents = List.copyOf(documents);
  }
}
