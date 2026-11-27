package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.DocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * The part counts of all passages come from one query, a passage's own count wins, and a document
 * without a usable count is absent.
 */
class DocumentPartCountsTest {

  private final DocumentRepository repository = mock(DocumentRepository.class);
  private final DocumentPartCounts partCounts = new DocumentPartCounts(repository);

  @Test
  void looksUpEveryDocumentOfThePassagesInOneQuery() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID empty = UUID.randomUUID();
    when(repository.findChunkCountsByIdIn(Set.of(first, second, empty)))
        .thenReturn(List.of(count(first, 12), count(second, 1), count(empty, 0)));

    Map<String, Integer> counts =
        partCounts.of(
            List.of(
                passage(first.toString(), 0),
                passage(first.toString(), 5),
                passage(second.toString(), 0),
                passage(empty.toString(), 0)));

    assertThat(counts)
        .containsOnly(Map.entry(first.toString(), 12), Map.entry(second.toString(), 1));
    verify(repository, times(1)).findChunkCountsByIdIn(any());
  }

  @Test
  void aPassageThatCarriesItsCountNeedsNoLookupAndOtherIdsAreSkipped() {
    Document attachment =
        new Document(
            "Anhang",
            Map.of(
                "document_id", UUID.randomUUID().toString(), "chunk_index", 1, "chunk_count", 3));
    Document foreign = passage("not-a-uuid", 0);
    Document withoutId = new Document("ohne", Map.of("chunk_index", 0));

    Map<String, Integer> counts = partCounts.of(List.of(attachment, foreign, withoutId));

    assertThat(counts)
        .containsOnly(Map.entry((String) attachment.getMetadata().get("document_id"), 3));
    verifyNoInteractions(repository);
  }

  @Test
  void noPassagesNoQuery() {
    assertThat(partCounts.of(List.of())).isEmpty();
    verifyNoInteractions(repository);
  }

  private static Document passage(String documentId, int index) {
    return new Document("Text", Map.of("document_id", documentId, "chunk_index", index));
  }

  private static DocumentRepository.DocumentChunkCount count(UUID id, int chunkCount) {
    return new DocumentRepository.DocumentChunkCount() {
      @Override
      public UUID getId() {
        return id;
      }

      @Override
      public int getChunkCount() {
        return chunkCount;
      }
    };
  }
}
