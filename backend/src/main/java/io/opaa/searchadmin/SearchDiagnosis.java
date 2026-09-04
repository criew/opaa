package io.opaa.searchadmin;

import io.opaa.query.RetrievalExplanation;
import io.opaa.query.SearchedLibraryRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One diagnosis run's result.
 *
 * <p>{@code explanation} is the pipeline's own protocol, handed on unchanged - not a reconstruction
 * and not a filtered copy. Everything else in this record is context for reading it: which
 * libraries the run was allowed to search, which queries the search stages ran, what the final
 * selection was, and how the opaque document keys inside the protocol read in German.
 *
 * @param permissionProfileName the profile's name, or {@code null} for a run in the caller's own
 *     rights context. Data, not a label: the German wording is the API mapper's business.
 * @param executedAt when the run happened. The diagnosis answers the Jetzt-Zustand; this dates the
 *     answer rather than making the tool a Zugriffshistorien-Nachweis.
 * @param lockedLibraryCount how many libraries of the target rights context were left out as
 *     diagnosegesperrt (Leitplanke (e)); their existence is named, nothing from inside them is.
 *     Always 0 outside the person context, where the lock does not apply.
 * @param trackedDocument present only when the query named a document to follow.
 */
public record SearchDiagnosis(
    String question,
    DiagnosisContextType contextType,
    String permissionProfileName,
    Instant executedAt,
    List<SearchedLibraryRef> searchScope,
    List<String> searchQueries,
    RetrievalExplanation explanation,
    List<SelectedChunk> selection,
    Map<String, DocumentDescriptor> documentsByKey,
    int lockedLibraryCount,
    TrackedDocumentVerdict trackedDocument) {

  /** One chunk of the final selection, at its 1-based position. */
  public record SelectedChunk(int rank, String chunkId, String documentKey) {}
}
