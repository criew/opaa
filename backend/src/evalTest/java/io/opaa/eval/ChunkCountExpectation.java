package io.opaa.eval;

import java.util.Comparator;
import java.util.List;

/**
 * The chunk-count property a domain's corpus is expected to have after the real,
 * production-configured {@code TokenTextSplitter} runs (issue #721, ADR-0010 Nachtrag). Two shapes,
 * one mechanism: a domain declares what it expects, and a violation is always a hard abort, never a
 * tolerance case — only *what* counts as a violation differs.
 *
 * <ul>
 *   <li>{@code ONE_CHUNK} — every document must produce <b>exactly</b> one chunk. This is the
 *       original Ein-Chunk-Invariante (ADR-0010) and stays exactly as strict for {@code
 *       comic-characters}: unchanged behavior, unchanged wording, unchanged abort.
 *   <li>{@code MIN_CHUNKS_PER_DOCUMENT} — every document must produce <b>at least</b> {@code
 *       minChunksPerDocument} chunks. For a domain built specifically to exercise multi-chunk
 *       documents (e.g. the upcoming #234 domain), a document that unexpectedly collapses to one
 *       chunk is exactly as much a signal of drift as a comic-characters document that unexpectedly
 *       splits into two.
 * </ul>
 *
 * <p>Deliberately not a boolean flag on the harness: ADR-0010's original wording treated the
 * invariant as a property "des Evaluierungskorpus" (singular, implicitly comic-characters). Issue
 * #721 turns it into a per-domain property so a future domain can declare a different, still
 * strictly-enforced expectation instead of the harness silently reusing comic-characters' rule for
 * every corpus.
 */
public record ChunkCountExpectation(Kind kind, int minChunksPerDocument) {

  public enum Kind {
    ONE_CHUNK,
    MIN_CHUNKS_PER_DOCUMENT
  }

  public ChunkCountExpectation {
    if (kind == Kind.MIN_CHUNKS_PER_DOCUMENT && minChunksPerDocument < 2) {
      throw new IllegalArgumentException(
          "MIN_CHUNKS_PER_DOCUMENT must require at least 2 chunks (otherwise it is just ONE_CHUNK "
              + "in disguise), got "
              + minChunksPerDocument);
    }
  }

  public static ChunkCountExpectation exactlyOneChunk() {
    return new ChunkCountExpectation(Kind.ONE_CHUNK, 1);
  }

  public static ChunkCountExpectation atLeast(int minChunksPerDocument) {
    return new ChunkCountExpectation(Kind.MIN_CHUNKS_PER_DOCUMENT, minChunksPerDocument);
  }

  /** One indexed document's real chunk count, as produced by the actual splitter run. */
  public record DocumentChunkCount(String fileName, int chunkCount) {}

  public record Violation(String fileName, int chunkCount, String reason) {}

  /**
   * Evaluates every document's real chunk count against this expectation. Pure function — no
   * Spring, no I/O — so both shapes are unit-testable without Docker (issue #721 acceptance
   * criterion: the multi-chunk invariant provably aborts on violation).
   */
  public List<Violation> violations(List<DocumentChunkCount> documents) {
    return switch (kind) {
      case ONE_CHUNK ->
          documents.stream()
              .filter(d -> d.chunkCount() != 1)
              .map(
                  d ->
                      new Violation(
                          d.fileName(),
                          d.chunkCount(),
                          "expected exactly 1 chunk (Ein-Chunk-Invariante, ADR-0010), got "
                              + d.chunkCount()))
              .sorted(Comparator.comparing(Violation::fileName))
              .toList();
      case MIN_CHUNKS_PER_DOCUMENT ->
          documents.stream()
              .filter(d -> d.chunkCount() < minChunksPerDocument)
              .map(
                  d ->
                      new Violation(
                          d.fileName(),
                          d.chunkCount(),
                          "expected at least "
                              + minChunksPerDocument
                              + " chunks (Mehr-Chunk-Invariante, ADR-0010 Nachtrag), got "
                              + d.chunkCount()))
              .sorted(Comparator.comparing(Violation::fileName))
              .toList();
    };
  }

  /** Human-readable description of this expectation, for reports and log messages. */
  public String describe() {
    return switch (kind) {
      case ONE_CHUNK -> "genau 1 Chunk je Dokument (Ein-Chunk-Invariante, ADR-0010)";
      case MIN_CHUNKS_PER_DOCUMENT ->
          "mindestens " + minChunksPerDocument + " Chunks je Dokument (Mehr-Chunk-Invariante)";
    };
  }
}
