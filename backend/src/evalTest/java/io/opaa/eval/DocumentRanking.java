package io.opaa.eval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a chunk-ranked hit list into a document-ranked list (issue #721, ADR-0012 Nachtrag).
 *
 * <p>The harness has always measured document-wise: a document's rank is the rank of its
 * best-placed chunk, deduplicated by {@code file_name}, first occurrence wins (this used to be the
 * private {@code dedupeByFileName} in {@code RetrievalEvaluationHarnessTest} — made explicit and
 * pure here per issue #721's technical note, not reinvented). For a one-chunk-per-document corpus
 * (comic-characters, ADR-0010) a chunk-bound {@code similaritySearch(topK=10)} and a document-bound
 * one are the same call, because deduplication never removes anything — that is exactly why
 * regenerating the comic-characters baseline against this class is expected to produce
 * bit-identical numbers (see the PR description's before/after comparison).
 *
 * <p>For a multi-chunk corpus they are not the same call: ten chunks can collapse to three or four
 * distinct documents, silently shrinking the measured window from k=10 documents to k=3 or 4.
 * {@link #documentTopKWindowSize} computes how large a *chunk*-bound search has to be so that,
 * after deduplication, at least {@code documentTopK} distinct documents are available to truncate
 * to — the "Fensterkorrektur" from issue #721. It is a single deterministic multiplication, not an
 * adaptive retry loop (issue #721's technical note): {@code documentTopK · maxChunksPerDocument},
 * where {@code maxChunksPerDocument} is a per-domain upper-bound estimate ({@link
 * EvalDomainConfig}), not a value discovered at run time.
 */
public final class DocumentRanking {

  private DocumentRanking() {}

  /**
   * The chunk-search window size ({@code chunkTopK}) needed to reliably reach {@code documentTopK}
   * distinct documents after deduplication, given the domain's declared upper bound on chunks per
   * document. Deterministic and cheap — no search happens here, this only sizes the *next* search.
   */
  public static int documentTopKWindowSize(int documentTopK, int maxChunksPerDocument) {
    if (documentTopK <= 0) {
      throw new IllegalArgumentException("documentTopK must be positive, got " + documentTopK);
    }
    if (maxChunksPerDocument <= 0) {
      throw new IllegalArgumentException(
          "maxChunksPerDocument must be positive, got " + maxChunksPerDocument);
    }
    return documentTopK * maxChunksPerDocument;
  }

  /**
   * Deduplicates a chunk-ranked hit list to a document-ranked one: a document's rank is the rank of
   * its first (best) occurring chunk, {@code file_name} identifies a document. Unbounded — callers
   * that only need the top {@code documentTopK} documents call {@link #truncate} afterwards, kept
   * separate so the "how many distinct documents did this window actually reach" question (see
   * {@link DocumentWindowResult}) can be answered before truncation discards the evidence.
   */
  public static List<String> dedupeToDocuments(List<String> chunkFileNamesInRankOrder) {
    Set<String> seen = new LinkedHashSet<>();
    for (String fileName : chunkFileNamesInRankOrder) {
      if (fileName != null) {
        seen.add(fileName);
      }
    }
    return List.copyOf(seen);
  }

  /** Result of applying the document-bound window to a chunk-ranked hit list. */
  public record DocumentWindowResult(
      List<String> rankedFileNames, int distinctDocumentsReached, boolean reachedDocumentTopK) {}

  /**
   * Applies the full document-bound window: deduplicate, then truncate to {@code documentTopK}.
   * {@link DocumentWindowResult#reachedDocumentTopK()} is {@code false} when the chunk-ranked input
   * — already sized via {@link #documentTopKWindowSize} — still did not surface {@code
   * documentTopK} distinct documents (e.g. because the corpus itself has fewer than {@code
   * documentTopK} documents). Issue #721: that case is reported explicitly, not silently measured
   * against a smaller window.
   */
  public static DocumentWindowResult applyDocumentWindow(
      List<String> chunkFileNamesInRankOrder, int documentTopK) {
    List<String> deduped = dedupeToDocuments(chunkFileNamesInRankOrder);
    int distinctReached = deduped.size();
    List<String> truncated = deduped.stream().limit(documentTopK).toList();
    return new DocumentWindowResult(truncated, distinctReached, distinctReached >= documentTopK);
  }
}
