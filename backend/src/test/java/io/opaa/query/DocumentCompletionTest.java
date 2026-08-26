package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Unit tests for {@link DocumentCompletion#complete}'s post-fusion document-vervollständigung
 * (#932).
 */
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
   * Verdrängungsregel: only a document already holding at least two selected chunks may give one
   * up, and always its globally weakest one - here doc A (two chunks) loses its weaker chunk to doc
   * B's second chunk once the budget is full.
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
   * No document in {@code selection} holds a second chunk it could give up (every document has
   * exactly one) - the budget stays exactly as fusion/MMR left it, document diversity is never
   * reduced.
   */
  @Test
  void doesNotEvictWhenNoDocumentHoldsASpareChunk() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docB = chunk("b-0", "doc-b", 0.8);
    Document docC = chunk("c-0", "doc-c", 0.7);
    Document docASibling = chunk("a-1", "doc-a", 0.6);
    List<Document> selection = List.of(docA, docB, docC);
    List<Document> candidatePool = List.of(docA, docB, docC, docASibling);

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

  @Test
  void neverGrowsPastTheOverallBudgetEvenWithSpareCandidates() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docB = chunk("b-0", "doc-b", 0.8);
    Document sibling = chunk("a-1", "doc-a", 0.7);
    List<Document> selection = List.of(docA, docB);
    List<Document> candidatePool = List.of(docA, docB, sibling);

    List<Document> result = DocumentCompletion.complete(selection, candidatePool, 2, 2);

    // The budget is already exhausted by two distinct documents (each with one chunk) - neither
    // holds a second chunk to give up, so the sibling cannot be admitted (see
    // #doesNotEvictWhenNoDocumentHoldsASpareChunk).
    assertThat(result).hasSize(2);
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
}
