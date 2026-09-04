package io.opaa.searchadmin;

import io.opaa.query.RetrievalStageName;
import io.opaa.query.VerdictReason;
import java.util.UUID;

/**
 * What became of one specific document in one diagnosis run - the answer to the question the whole
 * tool exists for: was the document never found (an indexing or chunking problem), or was it found
 * and displaced at a named stage (a ranking problem)?
 *
 * <p>{@code fileName}, {@code libraryId} and {@code libraryName} are {@code null} when the document
 * lies outside the searched scope of a foreign rights context: naming it would describe a library
 * that context did not search - and for {@link Outcome#IN_LOCKED_AREA} it would be exactly the
 * Dokumenttitel Leitplanke (e) rules out.
 *
 * @param displacedAtStage set only for {@link Outcome#DISPLACED}: the <b>last</b> stage that
 *     dropped one of this document's chunks. Last, not first - a chunk dropped from one candidate
 *     list may still survive in another, so the earliest drop is not where the document was
 *     actually lost.
 * @param displacedReason the reason recorded with that drop.
 */
public record TrackedDocumentVerdict(
    UUID documentId,
    String fileName,
    UUID libraryId,
    String libraryName,
    Outcome outcome,
    RetrievalStageName displacedAtStage,
    VerdictReason displacedReason,
    int retrievedChunkCount,
    int selectedChunkCount) {

  public enum Outcome {

    /** The rights context may not read this document's library, so no search could reach it. */
    OUTSIDE_SEARCH_SCOPE,

    /**
     * The document's library is diagnosegesperrt and was therefore left out of the run. Follows
     * from the library's lock state alone and is therefore the same verdict whether or not the
     * target person may read that library - the diagnosis makes no statement about a locked area at
     * all (Leitplanke (e)).
     */
    IN_LOCKED_AREA,

    /** Inside the scope, but no search stage returned any of its chunks. */
    NOT_RETRIEVED,

    /** Found, then dropped at a named stage. */
    DISPLACED,

    /** In the Endauswahl. */
    IN_FINAL_SELECTION
  }
}
