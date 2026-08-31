package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker-free, synthetic proof of the two issue #721 acceptance criteria that otherwise need a real
 * multi-chunk corpus and an embedding run to demonstrate:
 *
 * <ol>
 *   <li>"Ein Lauf mit dem alten, chunkbezogenen topK auf demselben mehrchunkigen Korpus liefert
 *       messbar andere nDCG@10-/Recall@10-Werte als der neue, dokumentbezogene."
 *   <li>"Der Harness erreicht auf einem mehrchunkigen Korpus nachweislich documentTopK=10
 *       unterschiedliche Dokumente."
 * </ol>
 *
 * <p>Rather than standing up Testcontainers (pgvector + Ollama) for a tiny fixture corpus just to
 * reproduce a purely mechanical property of {@link DocumentRanking}, this test simulates the exact
 * chunk-ranked hit list a real similarity search over a small, deliberately multi-chunk corpus
 * could plausibly return, and feeds it through the same production code path (<code>
 * DocumentRanking.applyDocumentWindow</code> and <code>RetrievalMetrics.evaluate</code>) both
 * harness call sites use. What changes between "old" and "new" is exactly the one thing #721
 * changed: whether the chunk search was sized to widen for deduplication (issue #721 code review,
 * Wichtig 2 — coordinator decision to build this rather than a real Testcontainers fixture, given a
 * synthetic scenario constructed this way is fully mechanical and does not depend on embedding
 * behavior at all).
 *
 * <h2>The synthetic corpus</h2>
 *
 * Ten documents. {@code doc-big} is a multi-chunk document contributing 8 chunks, all ranked ahead
 * of every other document's single chunk — the exact failure mode #721 describes: a document that
 * crowds out the ranked list purely by chunk count, not by being more relevant 8 times over. {@code
 * doc-2} through {@code doc-10} each contribute exactly one chunk, ranked in file-name order after
 * {@code doc-big}'s chunks. Total: 17 chunks across 10 documents. This mirrors the real corpus
 * shape #234 exists to introduce (see issue #721's Kontext) at a scale a unit test can construct by
 * hand.
 */
class DocumentWindowRegressionProofTest {

  private static final Logger log =
      LoggerFactory.getLogger(DocumentWindowRegressionProofTest.class);

  private static final int DOCUMENT_TOP_K = 10;
  private static final int MAX_CHUNKS_PER_DOCUMENT = 8;

  /**
   * The 17 raw chunk hits a similarity search over the synthetic corpus could return, in rank
   * order.
   */
  private static List<String> rankedChunkFileNames() {
    List<String> ranked = new ArrayList<>();
    for (int i = 0; i < MAX_CHUNKS_PER_DOCUMENT; i++) {
      ranked.add("doc-big"); // 8 chunks of the same, chunk-count-dominant document
    }
    for (int i = 2; i <= 10; i++) {
      ranked.add("doc-" + i); // one chunk each, ranked after doc-big's 8
    }
    return List.copyOf(ranked); // 8 + 9 = 17 chunks total
  }

  private static GoldenCase caseExpecting(String... expectedDocuments) {
    return new GoldenCase(
        "synthetic-window-proof",
        "synthetic",
        "synthetic multi-chunk query",
        List.of(expectedDocuments),
        "synthetic",
        "synthetic",
        "en",
        "synthetic",
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void oldChunkBoundTopKStarvesTheRankedListToThreeDistinctDocuments() {
    // "Old" semantics (pre-#721): similaritySearch(topK=documentTopK) with no widening for
    // deduplication — chunkTopK == documentTopK == 10, exactly what RetrievalEvaluationHarnessTest
    // did before this PR. Deduplicating the first 10 raw chunk hits (doc-big x8 + doc-2 + doc-3)
    // yields only 3 distinct documents.
    List<String> topTenRawChunks = rankedChunkFileNames().subList(0, DOCUMENT_TOP_K);

    var oldWindow = DocumentRanking.applyDocumentWindow(topTenRawChunks, DOCUMENT_TOP_K);

    assertThat(oldWindow.rankedFileNames()).containsExactly("doc-big", "doc-2", "doc-3");
    assertThat(oldWindow.distinctDocumentsReached()).isEqualTo(3);
    assertThat(oldWindow.reachedDocumentTopK()).isFalse();
  }

  @Test
  void newDocumentBoundWindowReachesAllTenDistinctDocuments() {
    // "New" semantics (#721): chunkTopK = documentTopK * maxChunksPerDocument = 10 * 8 = 80 — wide
    // enough to include every one of the corpus's 17 chunks, so deduplication reaches all 10
    // distinct documents. Issue #721 acceptance criterion: "Der Harness erreicht auf einem
    // mehrchunkigen Korpus nachweislich documentTopK=10 unterschiedliche Dokumente."
    int chunkTopK = DocumentRanking.documentTopKWindowSize(DOCUMENT_TOP_K, MAX_CHUNKS_PER_DOCUMENT);
    assertThat(chunkTopK).isEqualTo(80);
    // similaritySearch(topK=80) over a 17-chunk corpus returns all 17 chunks — simulated directly
    // since this test does not run a real search.
    List<String> allSeventeenRawChunks = rankedChunkFileNames();

    var newWindow = DocumentRanking.applyDocumentWindow(allSeventeenRawChunks, DOCUMENT_TOP_K);

    assertThat(newWindow.rankedFileNames())
        .containsExactly(
            "doc-big", "doc-2", "doc-3", "doc-4", "doc-5", "doc-6", "doc-7", "doc-8", "doc-9",
            "doc-10");
    assertThat(newWindow.distinctDocumentsReached()).isEqualTo(10);
    assertThat(newWindow.reachedDocumentTopK()).isTrue();
  }

  @Test
  void theTwoWindowsProduceMeasurablyDifferentNdcgAndRecallForACaseExpectingCrowdedOutDocuments() {
    // A golden case whose expected documents (doc-5, doc-8) only ever surface once the window is
    // widened — exactly the scenario #721's Kontext describes: nDCG@10/Recall@10 would silently be
    // computed over 3 documents, not 10, without this fix.
    GoldenCase goldenCase = caseExpecting("doc-5", "doc-8");

    List<String> topTenRawChunks = rankedChunkFileNames().subList(0, DOCUMENT_TOP_K);
    var oldWindow = DocumentRanking.applyDocumentWindow(topTenRawChunks, DOCUMENT_TOP_K);
    var oldResult = RetrievalMetrics.evaluate(goldenCase, oldWindow.rankedFileNames());

    List<String> allSeventeenRawChunks = rankedChunkFileNames();
    var newWindow = DocumentRanking.applyDocumentWindow(allSeventeenRawChunks, DOCUMENT_TOP_K);
    var newResult = RetrievalMetrics.evaluate(goldenCase, newWindow.rankedFileNames());

    // Old (chunk-bound): neither doc-5 nor doc-8 is in the crowded-out 3-document window.
    assertThat(oldResult.ndcgAt10()).isEqualTo(0.0);
    assertThat(oldResult.recallAt10()).isEqualTo(0.0);

    // New (document-bound): both are in the 10-document window, at ranks 5 and 8 respectively
    // (doc-big=1, doc-2=2, doc-3=3, doc-4=4, doc-5=5, doc-6=6, doc-7=7, doc-8=8, ...).
    // DCG = 1/log2(5+1) + 1/log2(8+1); IDCG (ideal ranks 1,2) = 1/log2(2) + 1/log2(3).
    double expectedDcg = 1.0 / (Math.log(6) / Math.log(2)) + 1.0 / (Math.log(9) / Math.log(2));
    double expectedIdcg = 1.0 / (Math.log(2) / Math.log(2)) + 1.0 / (Math.log(3) / Math.log(2));
    double expectedNdcg = expectedDcg / expectedIdcg;
    assertThat(newResult.ndcgAt10())
        .isCloseTo(expectedNdcg, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(newResult.recallAt10()).isEqualTo(1.0);

    // The measurable difference the issue #721 acceptance criterion demands — both numbers belong
    // in the PR description verbatim.
    log.info(
        "Alter (chunkbezogener) topK: nDCG@10={}, Recall@10={} — Neuer (dokumentbezogener) topK: "
            + "nDCG@10={}, Recall@10={}",
        String.format(java.util.Locale.ROOT, "%.3f", oldResult.ndcgAt10()),
        String.format(java.util.Locale.ROOT, "%.3f", oldResult.recallAt10()),
        String.format(java.util.Locale.ROOT, "%.3f", newResult.ndcgAt10()),
        String.format(java.util.Locale.ROOT, "%.3f", newResult.recallAt10()));

    assertThat(newResult.ndcgAt10()).isGreaterThan(oldResult.ndcgAt10());
    assertThat(newResult.recallAt10()).isGreaterThan(oldResult.recallAt10());
  }
}
