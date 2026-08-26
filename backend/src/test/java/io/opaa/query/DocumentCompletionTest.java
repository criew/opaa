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
