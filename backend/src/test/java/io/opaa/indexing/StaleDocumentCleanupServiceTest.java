package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Unit-level coverage of {@link StaleDocumentCleanupService}'s children-before-parents delete order
 * (ADR-0022, Entscheidung 4) - {@code StaleDocumentCleanupIntegrationTest} already covers the
 * class's pre-existing (library, sourceType)-scoped behaviour end-to-end against a real schema;
 * this class isolates the one new invariant that needs its own proof.
 */
class StaleDocumentCleanupServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final VectorChunkStore vectorChunkStore = mock(VectorChunkStore.class);
  private final StaleDocumentCleanupService service =
      new StaleDocumentCleanupService(documentRepository, vectorChunkStore);

  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          ORGANIZATION_ID,
          "Bibliothek",
          null,
          UUID.randomUUID(),
          LibraryVisibility.PRIVATE,
          false,
          DocumentSourceType.RSS_FEED,
          null,
          null,
          null,
          null,
          false);

  /**
   * {@code findByLibraryIdAndSourceType} carries no {@code ORDER BY} - both the vanished parent and
   * its vanished attachment child are in the same batch here, and only sorting them children-first
   * before deleting each in turn (not the repository's own unordered result order, deliberately
   * stubbed parent-before-child here) avoids failing {@code fk_documents_parent} by removing the
   * parent first.
   */
  @Test
  void deletesAVanishedAttachmentBeforeItsOwnVanishedParent() {
    Document parent = new Document("Eintrag", "https://feed.example/entry", "text/html", 10L);
    parent.setLibraryId(library.getId());
    Document child =
        new Document("Anlage", "https://feed.example/anlage.pdf", "application/pdf", 5L);
    child.setLibraryId(library.getId());
    child.setParentDocumentId(parent.getId());
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.RSS_FEED))
        .thenReturn(List.of(parent, child));
    IndexingRunEventRecorder events =
        new IndexingRunEventRecorder(mock(IndexingRunEventRepository.class), null, null);

    int removed =
        service.cleanupVanished(
            library,
            DocumentSourceType.RSS_FEED,
            // Neither path is in currentFilePaths - both vanished this run.
            Set.of("https://unrelated.example/still-there"),
            events);

    assertThat(removed).isEqualTo(2);
    InOrder order = inOrder(documentRepository, vectorChunkStore);
    order.verify(vectorChunkStore).deleteByDocumentId(child.getId());
    order.verify(documentRepository).delete(child);
    order.verify(vectorChunkStore).deleteByDocumentId(parent.getId());
    order.verify(documentRepository).delete(parent);
  }
}
