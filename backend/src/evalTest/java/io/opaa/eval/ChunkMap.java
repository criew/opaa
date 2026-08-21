package io.opaa.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-document chunk map the harness writes as a run artifact (issue #721): for a given
 * document, which chunks it split into, at which character positions, and which chunk (if any)
 * every applicable golden case's {@code answer_span} landed in. Nebenprodukt (byproduct) of a run,
 * not a generator assumption — computed from the real chunk texts the production {@code
 * ChunkingService} produced, never from a token count re-implemented here (ADR-0010's "der
 * Python-Generator zählt bewusst keine Tokens").
 *
 * <p>Pure and Docker-free: takes the document's full text and its already-produced chunk texts (the
 * harness reads both from the real, production-configured pipeline — see {@code
 * RetrievalEvaluationHarnessTest}) and locates each chunk's character offset within the source text
 * via {@link String#indexOf}. Chunk texts are literal substrings of the source document (the
 * splitter does not rewrite content), so this is exact, not an approximation — the only edge case
 * is a chunk text that recurs verbatim earlier in the document (e.g. a repeated boilerplate
 * sentence), handled by always searching forward from the end of the previous chunk's match.
 */
public final class ChunkMap {

  private ChunkMap() {}

  public record ChunkEntry(int index, int startChar, int endChar) {}

  public record DocumentChunkMap(
      String fileName,
      int chunkCount,
      List<ChunkEntry> chunks,
      // golden-case id -> chunk index the case's answer_span was found in; a case whose span was
      // not found in any of this document's chunks is simply absent from the map, not present with
      // a sentinel — this is a lookup, not an assertion that every span must resolve.
      Map<String, Integer> answerSpanChunkIndexByCaseId) {}

  /**
   * @param documentText the full parsed text of the source document, before chunking.
   * @param chunkTexts the chunk texts produced for this document, in split order.
   * @param answerSpansByCaseId golden-case id -> literal {@code answer_span} text, restricted to
   *     cases whose expected documents include this document (callers pre-filter; this method does
   *     not know about {@code expected_documents}).
   */
  public static DocumentChunkMap build(
      String fileName,
      String documentText,
      List<String> chunkTexts,
      Map<String, String> answerSpansByCaseId) {
    List<ChunkEntry> entries = new ArrayList<>(chunkTexts.size());
    int searchFrom = 0;
    for (int i = 0; i < chunkTexts.size(); i++) {
      String chunkText = chunkTexts.get(i);
      int start = documentText.indexOf(chunkText, searchFrom);
      if (start < 0) {
        // Fallback: search from the very start. Should not happen for a genuine TokenTextSplitter
        // output (chunks are literal substrings in order), but never let a chunk-map computation
        // fail the run over a positional quirk — an unresolved offset is reported as -1/-1 rather
        // than thrown.
        start = documentText.indexOf(chunkText);
      }
      int end = start < 0 ? -1 : start + chunkText.length();
      entries.add(new ChunkEntry(i, start, end));
      searchFrom = start >= 0 ? end : searchFrom;
    }

    Map<String, Integer> spanIndex = new LinkedHashMap<>();
    answerSpansByCaseId.forEach(
        (caseId, span) -> {
          if (span == null || span.isBlank()) {
            return;
          }
          for (int i = 0; i < chunkTexts.size(); i++) {
            if (chunkTexts.get(i).contains(span)) {
              spanIndex.put(caseId, i);
              break;
            }
          }
        });

    return new DocumentChunkMap(fileName, chunkTexts.size(), List.copyOf(entries), spanIndex);
  }
}
