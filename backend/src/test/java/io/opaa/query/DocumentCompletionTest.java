package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Unit tests for {@link DocumentCompletion#complete}'s post-fusion document completion (#932). */
class DocumentCompletionTest {

  private static Document chunk(String id, String documentId, double score) {
    return Document.builder()
        .id(id)
        .text(id)
        .metadata(Map.of("document_id", documentId, "file_name", documentId + ".md"))
        .score(score)
        .build();
  }

  @Test
  void pullsInASiblingChunkOfAnAlreadySelectedDocumentWhenBudgetHasRoom() {
    Document selected = chunk("a-0", "doc-a", 0.9);
    Document sibling = chunk("a-1", "doc-a", 0.5);
    List<Document> selection = List.of(selected);
    List<Document> candidatePool = List.of(selected, sibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 2);

    assertThat(result).extracting(Document::getId).containsExactly("a-0", "a-1");
  }

  /**
   * The #912/#932 reproduction shape: a combined question ranks doc A's introduction chunk into the
   * final selection but leaves its fee-table chunk in the candidate pool, crowded out only by
   * chunks of <em>other</em> documents - with spare budget, completion recovers it without needing
   * to evict anything.
   */
  @Test
  void recoversTheDetailChunkOfADocumentAlreadyRepresentedByItsIntroductionChunk() {
    Document introduction = chunk("intro", "doc-personalausweis", 0.9);
    Document feeTable = chunk("fees", "doc-personalausweis", 0.55);
    Document otherTopicA = chunk("other-a", "doc-other-a", 0.8);
    Document otherTopicB = chunk("other-b", "doc-other-b", 0.7);
    List<Document> selection = List.of(introduction, otherTopicA, otherTopicB);
    List<Document> candidatePool = List.of(introduction, feeTable, otherTopicA, otherTopicB);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 4);

    assertThat(result).extracting(Document::getId).contains("intro", "fees");
  }

  /**
   * Eviction rule: only a document already holding at least two selected chunks may give one up,
   * and always its globally weakest one - here doc A (two chunks) loses its weaker chunk to doc B's
   * second chunk once the budget is full.
   */
  @Test
  void evictsTheWeakestChunkOfAnOverrepresentedDocumentToMakeRoomForASiblingElsewhere() {
    Document docAStrong = chunk("a-0", "doc-a", 0.9);
    Document docAWeak = chunk("a-1", "doc-a", 0.8);
    Document docBFirst = chunk("b-0", "doc-b", 0.7);
    Document docBSecond = chunk("b-1", "doc-b", 0.6);
    List<Document> selection = List.of(docAStrong, docAWeak, docBFirst);
    List<Document> candidatePool = List.of(docAStrong, docAWeak, docBFirst, docBSecond);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 3);

    assertThat(result).extracting(Document::getId).containsExactly("a-0", "b-0", "b-1");
  }

  /**
   * No document in {@code selection} holds a second chunk tier 1 could give up (every document has
   * exactly one), and the completing document (doc-c) ranks worse than every other chunk still
   * present - tier 2 also has no eligible victim, so the budget stays exactly as fusion/MMR left
   * it, document diversity is never reduced.
   */
  @Test
  void doesNotEvictWhenNoDocumentHoldsASpareChunkAndTheCompletingDocumentOutranksNoOther() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docB = chunk("b-0", "doc-b", 0.8);
    Document docC = chunk("c-0", "doc-c", 0.7);
    Document docCSibling = chunk("c-1", "doc-c", 0.6);
    List<Document> selection = List.of(docA, docB, docC);
    List<Document> candidatePool = List.of(docA, docB, docC, docCSibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 3);

    assertThat(result).extracting(Document::getId).containsExactly("a-0", "b-0", "c-0");
  }

  @Test
  void neverPullsInMoreThanMaxChunksPerDocument() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document sibling1 = chunk("a-1", "doc-a", 0.8);
    Document sibling2 = chunk("a-2", "doc-a", 0.7);
    List<Document> selection = List.of(docA);
    List<Document> candidatePool = List.of(docA, sibling1, sibling2);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 5);

    assertThat(result).extracting(Document::getId).containsExactly("a-0", "a-1");
  }

  /**
   * A completion via tier 2 replaces one selected chunk with another - it never grows {@code
   * result} past {@code overallBudget}, even though a candidate is genuinely admitted here (doc-a
   * evicts doc-b, the only other, worse-ranked document).
   */
  @Test
  void aTier2CompletionNeverGrowsPastTheOverallBudget() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docB = chunk("b-0", "doc-b", 0.8);
    Document sibling = chunk("a-1", "doc-a", 0.7);
    List<Document> selection = List.of(docA, docB);
    List<Document> candidatePool = List.of(docA, docB, sibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 2);

    assertThat(result).hasSize(2);
    assertThat(result).extracting(Document::getId).containsExactly("a-0", "a-1");
  }

  @Test
  void maxChunksPerDocumentOfOneDisablesCompletionEntirely() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document sibling = chunk("a-1", "doc-a", 0.5);
    List<Document> selection = List.of(docA);
    List<Document> candidatePool = List.of(docA, sibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 1, 5);

    assertThat(result).extracting(Document::getId).containsExactly("a-0");
  }

  @Test
  void emptySelectionStaysEmpty() {
    List<Document> result = DocumentCompletion.complete(List.of(), List.of(), 2, 5);

    assertThat(result).isEmpty();
  }

  /**
   * Code review of #932's original PR: with two completable documents, an earlier completion must
   * never be undone by a later one within the same call - the earlier completion's document is
   * excluded as an eviction source once it has received a chunk, even though it now holds two
   * chunks itself. Without that exclusion, doc-b's completion below would evict doc-a's just-added
   * "a-1" right back out, leaving doc-a exactly where it started while doc-x permanently lost a
   * chunk for no net gain - strictly worse than doing nothing.
   */
  @Test
  void aCompletionNeverEvictsAChunkAnEarlierCompletionInTheSameCallJustAdded() {
    Document x0 = chunk("x-0", "doc-x", 0.95);
    Document x1 = chunk("x-1", "doc-x", 0.90);
    Document a0 = chunk("a-0", "doc-a", 0.85);
    Document b0 = chunk("b-0", "doc-b", 0.80);
    Document a1 = chunk("a-1", "doc-a", 0.50);
    Document b1 = chunk("b-1", "doc-b", 0.45);
    List<Document> selection = List.of(x0, x1, a0, b0);
    List<Document> candidatePool = List.of(x0, x1, a0, b0, a1, b1);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 4);

    // doc-a's completion (processed first, per selection order) evicts doc-x's weaker chunk and
    // is then protected: doc-b's later completion attempt finds no eligible eviction source left
    // (doc-x is down to one chunk, doc-a is protected) and simply does not complete - it must not
    // claw the slot back from doc-a.
    assertThat(result).extracting(Document::getId).containsExactly("x-0", "a-0", "b-0", "a-1");
  }

  /**
   * Code review of #932's original PR: eviction must pick the weakest chunk by its position in the
   * original, already-authoritative {@code selection} order - never by raw {@code
   * Document#getScore()}, which is only comparable within a single search vector (see {@code
   * ReciprocalRankFusion}'s Javadoc on why a cross-sub-query score comparison is exactly the #912
   * failure mode). Here the later-ranked chunk of the over-represented document deliberately
   * carries the higher raw score, so a score-based eviction would pick the wrong one.
   */
  @Test
  void evictsByOriginalSelectionRankNotByRawScore() {
    Document x0 = chunk("x-0", "doc-x", 0.50);
    Document x1 = chunk("x-1", "doc-x", 0.95);
    Document a0 = chunk("a-0", "doc-a", 0.90);
    Document a1 = chunk("a-1", "doc-a", 0.10);
    List<Document> selection = List.of(x0, x1, a0);
    List<Document> candidatePool = List.of(x0, x1, a0, a1);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 3);

    // x1 is later in selection (weaker by rank) despite its higher score - it must be the one
    // evicted, not x0.
    assertThat(result).extracting(Document::getId).containsExactly("x-0", "a-0", "a-1");
  }

  /**
   * Code review of #932's original PR: which unused sibling candidate is tried first must follow
   * its position in {@code candidatePool} (the search's own rank), not raw {@code
   * Document#getScore()} - the same cross-sub-query comparability concern as {@link
   * #evictsByOriginalSelectionRankNotByRawScore}. The pool lists the low-scoring candidate before
   * the high-scoring one; a score-based sort would try the high-scoring one first instead.
   */
  @Test
  void prefersSiblingCandidateByPoolPositionNotByRawScore() {
    Document a0 = chunk("a-0", "doc-a", 0.90);
    Document aEarlyLowScore = chunk("a-early", "doc-a", 0.10);
    Document aLateHighScore = chunk("a-late", "doc-a", 0.99);
    List<Document> selection = List.of(a0);
    List<Document> candidatePool = List.of(a0, aEarlyLowScore, aLateHighScore);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 2);

    assertThat(result).extracting(Document::getId).containsExactly("a-0", "a-early");
  }

  /**
   * The same chunk id can appear once per sub-query in a pooled multi-query candidate list (#923),
   * each instance carrying that sub-query's own raw score - deduped to the higher-scoring instance
   * before it is offered as a completion candidate, mirroring {@code ReciprocalRankFusion}'s own
   * duplicate-instance handling for the identical case.
   */
  @Test
  void dedupesAPoolCandidateAppearingInMoreThanOneSubQueryKeepingTheHigherScoringInstance() {
    Document a0 = chunk("a-0", "doc-a", 0.9);
    Document siblingLowScoreInstance = chunk("a-1", "doc-a", 0.2);
    Document siblingHighScoreInstance = chunk("a-1", "doc-a", 0.7);
    List<Document> selection = List.of(a0);
    List<Document> candidatePool = List.of(a0, siblingLowScoreInstance, siblingHighScoreInstance);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 2);

    assertThat(result).hasSize(2);
    Document added = result.get(1);
    assertThat(added.getId()).isEqualTo("a-1");
    assertThat(added.getScore()).isEqualTo(0.7);
  }

  @Test
  void ignoresCandidatesAlreadyInTheSelection() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docASibling = chunk("a-1", "doc-a", 0.5);
    List<Document> selection = List.of(docA, docASibling);
    // The pool still contains both chunks (as similaritySearch would return them) - completion
    // must not duplicate either.
    List<Document> candidatePool = List.of(docA, docASibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 5);

    assertThat(result).extracting(Document::getId).containsExactly("a-0", "a-1");
  }

  /**
   * Tier 2 (#932 scope v2) - the exact live-verification shape that revealed tier 1 alone is a
   * no-op: eight documents, each contributing exactly one chunk to the final selection, none
   * holding a spare chunk tier 1 could evict. The well-ranked document (doc-3, standing in for
   * {@code 001_personalausweis.md}) recovers its sibling (the fee chunk) by evicting the
   * lowest-ranked chunk of the whole selection (doc-7, standing in for the unrelated tail document
   * {@code 012_eid-karte...}).
   */
  @Test
  void evictsTheLowestRankedChunkOfTheSelectionWhenNoDocumentHoldsASpareChunkForTier1() {
    Document doc0 = chunk("doc0-0", "doc-0", 0.95);
    Document doc1 = chunk("doc1-0", "doc-1", 0.90);
    Document doc2 = chunk("doc2-0", "doc-2", 0.85);
    Document doc3 = chunk("doc3-0", "doc-3", 0.80);
    Document doc4 = chunk("doc4-0", "doc-4", 0.75);
    Document doc5 = chunk("doc5-0", "doc-5", 0.70);
    Document doc6 = chunk("doc6-0", "doc-6", 0.65);
    Document doc7 = chunk("doc7-0", "doc-7", 0.60);
    Document doc3Sibling = chunk("doc3-1", "doc-3", 0.20);
    List<Document> selection = List.of(doc0, doc1, doc2, doc3, doc4, doc5, doc6, doc7);
    List<Document> candidatePool =
        List.of(doc0, doc1, doc2, doc3, doc4, doc5, doc6, doc7, doc3Sibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 8);

    assertThat(result).hasSize(8);
    assertThat(result).extracting(Document::getId).doesNotContain("doc7-0");
    assertThat(result)
        .filteredOn(d -> QueryService.chunkGroupingKey(d).equals("doc-3"))
        .extracting(Document::getId)
        .containsExactlyInAnyOrder("doc3-0", "doc3-1");
  }

  /**
   * Rejection (a): the only other chunk present ranks strictly better than the completing document
   * (doc-b) - tier 2's strict-rank condition fails, so no eviction happens.
   */
  @Test
  void doesNotEvictAtTier2WhenTheOnlyOtherChunkOutranksTheCompletingDocument() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docB = chunk("b-0", "doc-b", 0.8);
    Document docBSibling = chunk("b-1", "doc-b", 0.5);
    List<Document> selection = List.of(docA, docB);
    List<Document> candidatePool = List.of(docA, docB, docBSibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 2);

    assertThat(result).extracting(Document::getId).containsExactly("a-0", "b-0");
  }

  /**
   * Rejection (c): {@code selection} represents only a single document - there is no other
   * document's chunk to evict at either tier, regardless of rank.
   */
  @Test
  void doesNotEvictAtTier2WhenSelectionRepresentsOnlyOneDocument() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docASibling = chunk("a-1", "doc-a", 0.5);
    List<Document> selection = List.of(docA);
    List<Document> candidatePool = List.of(docA, docASibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 1);

    assertThat(result).extracting(Document::getId).containsExactly("a-0");
  }

  /**
   * Rejection (b), mirroring {@link
   * #aCompletionNeverEvictsAChunkAnEarlierCompletionInTheSameCallJustAdded} for tier 2: doc-0
   * (processed first, per selection order) completes via tier 2, evicting doc-2 (the lowest-ranked
   * chunk) and gaining "d0-1". doc-1's own completion attempt then finds no eligible tier-2 victim
   * - "d0-1" is never eligible (it was itself added this call), and the one remaining original
   * chunk, "d0-0", ranks better than doc-1 - so doc-1 stays at its single chunk.
   */
  @Test
  void aTier2CompletionNeverEvictsAChunkAnEarlierTier2CompletionJustAdded() {
    Document d0 = chunk("d0-0", "doc-0", 0.9);
    Document d1 = chunk("d1-0", "doc-1", 0.8);
    Document d2 = chunk("d2-0", "doc-2", 0.7);
    Document d0Sibling = chunk("d0-1", "doc-0", 0.1);
    Document d1Sibling = chunk("d1-1", "doc-1", 0.6);
    List<Document> selection = List.of(d0, d1, d2);
    List<Document> candidatePool = List.of(d0, d1, d2, d0Sibling, d1Sibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 3);

    assertThat(result).extracting(Document::getId).containsExactly("d0-0", "d1-0", "d0-1");
  }

  /**
   * Interplay: within one call, tier 1 completes doc-b (evicting the weaker of doc-a, which already
   * held two chunks in the original selection) while doc-c, unable to find a tier-1 source of its
   * own (doc-a is back down to one chunk, doc-b is protected as this call's own completion), still
   * completes via tier 2 by evicting doc-d's single, lowest-ranked chunk.
   */
  @Test
  void tier1AndTier2BothCompleteWithinTheSameCall() {
    Document docAStrong = chunk("a-0", "doc-a", 0.95);
    Document docAWeak = chunk("a-1", "doc-a", 0.90);
    Document docBFirst = chunk("b-0", "doc-b", 0.85);
    Document docCFirst = chunk("c-0", "doc-c", 0.80);
    Document docDFiller = chunk("d-0", "doc-d", 0.10);
    Document docBSecond = chunk("b-1", "doc-b", 0.60);
    Document docCSecond = chunk("c-1", "doc-c", 0.55);
    List<Document> selection = List.of(docAStrong, docAWeak, docBFirst, docCFirst, docDFiller);
    List<Document> candidatePool =
        List.of(docAStrong, docAWeak, docBFirst, docCFirst, docDFiller, docBSecond, docCSecond);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 5);

    assertThat(result)
        .extracting(Document::getId)
        .containsExactlyInAnyOrder("a-0", "b-0", "b-1", "c-0", "c-1");
  }

  /**
   * Cap (Maintainer decision, #935 review): tier 2 may evict at most {@code max(1, overallBudget /
   * 4)} times per call - at {@code overallBudget=8} (the default {@code topK}), exactly 2. Three
   * documents here are each individually eligible for tier 2 (their own best rank strictly beats
   * the current selection-last chunk) - only the first two, processed in selection order, succeed;
   * the third's completion attempt is rejected once the cap is exhausted, even though its own
   * strict-rank condition would otherwise pass.
   */
  @Test
  void capsTier2EvictionsAtMaxOfOneOrOneQuarterOfTheOverallBudget() {
    Document doc0 = chunk("doc0-0", "doc-0", 0.95);
    Document doc1 = chunk("doc1-0", "doc-1", 0.90);
    Document doc2 = chunk("doc2-0", "doc-2", 0.85);
    Document doc3 = chunk("doc3-0", "doc-3", 0.80);
    Document doc4 = chunk("doc4-0", "doc-4", 0.75);
    Document doc5 = chunk("doc5-0", "doc-5", 0.70);
    Document doc6 = chunk("doc6-0", "doc-6", 0.65);
    Document doc7 = chunk("doc7-0", "doc-7", 0.60);
    Document doc0Sibling = chunk("doc0-1", "doc-0", 0.10);
    Document doc1Sibling = chunk("doc1-1", "doc-1", 0.09);
    Document doc2Sibling = chunk("doc2-1", "doc-2", 0.08);
    List<Document> selection = List.of(doc0, doc1, doc2, doc3, doc4, doc5, doc6, doc7);
    List<Document> candidatePool =
        List.of(
            doc0, doc1, doc2, doc3, doc4, doc5, doc6, doc7, doc0Sibling, doc1Sibling, doc2Sibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 8);

    assertThat(result).hasSize(8);
    assertThat(result).extracting(Document::getId).contains("doc0-1", "doc1-1");
    assertThat(result).extracting(Document::getId).doesNotContain("doc2-1", "doc6-0", "doc7-0");
  }
}
