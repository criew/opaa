package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Unit-level coverage of {@link StaleDocumentCleanupService}: the children-before-parents delete
 * order (ADR-0022, Entscheidung 4) and the reconciliation's fold-in of attachments whose parent is
 * present but was not re-parsed (Entscheidung 3). {@code StaleDocumentCleanupIntegrationTest}
 * covers the (library, sourceType)-scoped behaviour end-to-end against a real schema.
 */
class StaleDocumentCleanupServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final VectorChunkStore vectorChunkStore = mock(VectorChunkStore.class);
  private final StaleDocumentCleanupService service =
      new StaleDocumentCleanupService(documentRepository, vectorChunkStore);
  private final IndexingRunEventRecorder events =
      new IndexingRunEventRecorder(mock(IndexingRunEventRepository.class), null, null);

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

  private static SourceIndexingExecutor removingOnAbsence() {
    SourceIndexingExecutor executor = mock(SourceIndexingExecutor.class);
    when(executor.runModes())
        .thenReturn(Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
    return executor;
  }

  /**
   * {@code findByLibraryIdAndSourceType} carries no {@code ORDER BY} - both the vanished parent and
   * its vanished attachment child are in the same batch here, and only sorting them children-first
   * before deleting each in turn (not the repository's own unordered result order, deliberately
   * stubbed parent-before-child here) avoids failing {@code fk_documents_parent} by removing the
   * parent first.
   */
  @Test
  void deletesAVanishedAttachmentBeforeItsOwnVanishedParent() {
    Document parent = document("Eintrag", "https://feed.example/entry", null);
    Document child = document("Anlage", "https://feed.example/anlage.pdf", parent);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.RSS_FEED))
        .thenReturn(List.of(parent, child));

    int removed =
        service.cleanupVanished(
            library,
            DocumentSourceType.RSS_FEED,
            // Neither path is in currentFilePaths - both vanished this run.
            Set.of("https://unrelated.example/still-there"),
            events,
            removingOnAbsence(),
            IndexingRunMode.FULL);

    assertThat(removed).isEqualTo(2);
    InOrder order = inOrder(documentRepository, vectorChunkStore);
    order.verify(vectorChunkStore).deleteByDocumentId(child.getId());
    order.verify(documentRepository).delete(child);
    order.verify(vectorChunkStore).deleteByDocumentId(parent.getId());
    order.verify(documentRepository).delete(parent);
  }

  /**
   * A Mail-in-Mail chain nests an attachment inside an attachment (a forwarded {@code .eml} with
   * its own attachment) - two levels of {@code parent_document_id}. Stubbed in the order least
   * favorable to a naive one-level sort (grandchild first, then parent, then the intermediate
   * child) to prove the delete order is derived from actual nesting depth, not from the
   * repository's incidental result order.
   */
  @Test
  void deletesAVanishedGrandchildAttachmentBeforeItsIntermediateAndOutermostParents() {
    Document outerMail = document("Aussenmail.eml", "https://feed.example/outer", null);
    Document innerMail =
        document(
            "weitergeleitet.eml", "https://feed.example/outer/0/weitergeleitet.eml", outerMail);
    Document grandchildAttachment =
        document(
            "anlage.pdf",
            "https://feed.example/outer/0/weitergeleitet.eml/0/anlage.pdf",
            innerMail);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.RSS_FEED))
        .thenReturn(List.of(grandchildAttachment, outerMail, innerMail));

    int removed =
        service.cleanupVanished(
            library,
            DocumentSourceType.RSS_FEED,
            Set.of("https://unrelated.example/still-there"),
            events,
            removingOnAbsence(),
            IndexingRunMode.FULL);

    assertThat(removed).isEqualTo(3);
    InOrder order = inOrder(documentRepository, vectorChunkStore);
    order.verify(vectorChunkStore).deleteByDocumentId(grandchildAttachment.getId());
    order.verify(documentRepository).delete(grandchildAttachment);
    order.verify(vectorChunkStore).deleteByDocumentId(innerMail.getId());
    order.verify(documentRepository).delete(innerMail);
    order.verify(vectorChunkStore).deleteByDocumentId(outerMail.getId());
    order.verify(documentRepository).delete(outerMail);
  }

  // --- reconcile: the fold-in of ADR-0022, Entscheidung 3 -----------------------------------

  @Test
  void reconcilePreservesTheAttachmentsOfAPresentButNotReprocessedParentRecursively() {
    // The Nachtragsfall: an unchanged (checksum-skipped) mail was never re-parsed, so its
    // attachment rows - including a grandchild of a nested mail - are preserved from the
    // database, regardless of the rows' iteration order (grandchild listed before its parent).
    Document mail = document("unveraendert.eml", "/mail.eml", null);
    Document innerMail = document("weitergeleitet.eml", "/mail.eml/0/weitergeleitet.eml", mail);
    Document grandchild =
        document("anlage.pdf", "/mail.eml/0/weitergeleitet.eml/0/anlage.pdf", innerMail);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.FILESYSTEM))
        .thenReturn(List.of(grandchild, mail, innerMail));

    int removed =
        service.reconcile(
            library,
            DocumentSourceType.FILESYSTEM,
            Set.of("/mail.eml"),
            Set.of(),
            events,
            removingOnAbsence(),
            IndexingRunMode.FULL);

    assertThat(removed).isZero();
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void reconcileRemovesAnAttachmentAReprocessedParentDidNotReportAgain() {
    // For a mail that was actually re-parsed, only the attachments the attachment path
    // re-reported count as present - a row of a since-removed attachment falls away, while its
    // sibling that was re-reported stays.
    Document mail = document("mail.eml", "/mail.eml", null);
    Document kept = document("behalten.pdf", "/mail.eml/0/behalten.pdf", mail);
    Document gone = document("entfernt.pdf", "/mail.eml/1/entfernt.pdf", mail);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.FILESYSTEM))
        .thenReturn(List.of(mail, kept, gone));

    int removed =
        service.reconcile(
            library,
            DocumentSourceType.FILESYSTEM,
            Set.of("/mail.eml", "/mail.eml/0/behalten.pdf"),
            Set.of("/mail.eml", "/mail.eml/0/behalten.pdf"),
            events,
            removingOnAbsence(),
            IndexingRunMode.FULL);

    assertThat(removed).isEqualTo(1);
    verify(documentRepository).delete(gone);
    verify(documentRepository, never()).delete(kept);
    verify(documentRepository, never()).delete(mail);
  }

  @Test
  void reconcilePreservesTheChildrenOfAnUnchangedInnerMailInsideAReprocessedOuterMail() {
    // The mixed case: the outer mail was re-parsed (its direct attachment set is authoritative),
    // but the inner mail was merely confirmed unchanged - its own children were not rediscovered
    // and are preserved from the database.
    Document outer = document("aussen.eml", "/aussen.eml", null);
    Document inner = document("weitergeleitet.eml", "/aussen.eml/0/weitergeleitet.eml", outer);
    Document grandchild =
        document("anlage.pdf", "/aussen.eml/0/weitergeleitet.eml/0/anlage.pdf", inner);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.FILESYSTEM))
        .thenReturn(List.of(grandchild, outer, inner));

    int removed =
        service.reconcile(
            library,
            DocumentSourceType.FILESYSTEM,
            Set.of("/aussen.eml", "/aussen.eml/0/weitergeleitet.eml"),
            Set.of("/aussen.eml"),
            events,
            removingOnAbsence(),
            IndexingRunMode.FULL);

    assertThat(removed).isZero();
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void reconcileDeletesNothingForAnEmptyBestandAndLeavesTheCallersSetsUntouched() {
    Document orphan = document("alt.pdf", "/alt.pdf", null);
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.FILESYSTEM))
        .thenReturn(List.of(orphan));
    Set<String> current = Set.of();

    int removed =
        service.reconcile(
            library,
            DocumentSourceType.FILESYSTEM,
            current,
            Set.of(),
            events,
            removingOnAbsence(),
            IndexingRunMode.FULL);

    assertThat(removed).isZero();
    verify(documentRepository, never()).delete(any(Document.class));
    verify(documentRepository, never()).findByLibraryIdAndSourceType(any(), any());
  }

  @Test
  void reconcileRefusesARunModeThatKeepsOnAbsence() {
    SourceIndexingExecutor executor = mock(SourceIndexingExecutor.class);
    when(executor.runModes())
        .thenReturn(Map.of(IndexingRunMode.INCREMENTAL, VanishedDocumentPolicy.KEEP_ON_ABSENCE));

    assertThatThrownBy(
            () ->
                service.reconcile(
                    library,
                    DocumentSourceType.RSS_FEED,
                    Set.of("https://feed.example/entry"),
                    Set.of(),
                    events,
                    executor,
                    IndexingRunMode.INCREMENTAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("KEEP_ON_ABSENCE");
    verify(documentRepository, never()).findByLibraryIdAndSourceType(any(), any());
  }

  private Document document(String fileName, String filePath, Document parent) {
    Document document = new Document(fileName, filePath, "application/octet-stream", 5L);
    document.setLibraryId(library.getId());
    if (parent != null) {
      document.setParentDocumentId(parent.getId());
    }
    return document;
  }
}
