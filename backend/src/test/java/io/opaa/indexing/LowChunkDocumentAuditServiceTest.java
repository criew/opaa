package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of the one-time inventory check from ingestion-pipelines.md, Teil 3, Punkt 1
 * "Scan-Erkennung und Bestandsprüfung" (#1055): finding already-INDEXED documents with null or
 * auffällig wenigen Chunks, grouped per library.
 */
class LowChunkDocumentAuditServiceTest {

  private DocumentRepository documentRepository;
  private KnowledgeLibraryRepository libraryRepository;
  private LowChunkDocumentAuditService service;

  @BeforeEach
  void setUp() {
    documentRepository = mock(DocumentRepository.class);
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    service = new LowChunkDocumentAuditService(documentRepository, libraryRepository);
  }

  private static KnowledgeLibrary library(String name) {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(), name, null, UUID.randomUUID(), LibraryVisibility.PRIVATE, false);
  }

  private static Document indexedDocument(UUID libraryId, String fileName, long size, int chunks) {
    Document document = new Document(fileName, "/path/" + fileName, "application/pdf", size);
    document.setLibraryId(libraryId);
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(chunks);
    return document;
  }

  // findLowChunkDocuments groups by a HashMap keyed on libraryId and asks the repository with
  // that map's keySet() (a Set, order-independent) - a plain List.of(...) argument would never
  // match Mockito's equals()-based stubbing (a Set never equals a List, even with the same
  // elements), so every findAllById stub here matches on contents instead.
  private static Iterable<UUID> containingExactly(UUID... ids) {
    return argThat(actual -> new HashSet<>(toSet(actual)).equals(Set.of(ids)));
  }

  private static Set<UUID> toSet(Iterable<UUID> ids) {
    Set<UUID> result = new HashSet<>();
    ids.forEach(result::add);
    return result;
  }

  @Test
  void returnsAnEmptyListWhenNoDocumentIsAtOrBelowTheThreshold() {
    when(documentRepository.findByStatusAndChunkCountLessThanEqual(
            eq(DocumentStatus.INDEXED), anyInt()))
        .thenReturn(List.of());

    assertThat(service.findLowChunkDocuments(0)).isEmpty();
  }

  @Test
  void groupsFlaggedDocumentsPerLibraryWithFileNameSizeAndChunkCount() {
    KnowledgeLibrary libraryA = library("Satzungen");
    KnowledgeLibrary libraryB = library("Formulare");
    Document zeroChunkInA = indexedDocument(libraryA.getId(), "scan.pdf", 12_345L, 0);
    Document alsoZeroInA = indexedDocument(libraryA.getId(), "altakte.pdf", 999L, 0);
    Document zeroChunkInB = indexedDocument(libraryB.getId(), "vermerk.pdf", 42L, 0);

    when(documentRepository.findByStatusAndChunkCountLessThanEqual(DocumentStatus.INDEXED, 0))
        .thenReturn(List.of(zeroChunkInA, alsoZeroInA, zeroChunkInB));
    when(libraryRepository.findAllById(containingExactly(libraryA.getId(), libraryB.getId())))
        .thenReturn(List.of(libraryA, libraryB));

    List<LowChunkDocumentAuditService.LibraryLowChunkReport> reports =
        service.findLowChunkDocuments(0);

    assertThat(reports).hasSize(2);
    // Sorted by library name.
    assertThat(reports.get(0).libraryName()).isEqualTo("Formulare");
    assertThat(reports.get(0).documents())
        .extracting(LowChunkDocumentAuditService.LowChunkDocumentEntry::fileName)
        .containsExactly("vermerk.pdf");
    assertThat(reports.get(0).documents().getFirst().fileSize()).isEqualTo(42L);
    assertThat(reports.get(0).documents().getFirst().chunkCount()).isEqualTo(0);

    assertThat(reports.get(1).libraryName()).isEqualTo("Satzungen");
    // Sorted by file name within a library.
    assertThat(reports.get(1).documents())
        .extracting(LowChunkDocumentAuditService.LowChunkDocumentEntry::fileName)
        .containsExactly("altakte.pdf", "scan.pdf");
  }

  @Test
  void aLibraryThatNoLongerResolvesStillReportsItsDocumentsUnderAPlaceholderName() {
    UUID vanishedLibraryId = UUID.randomUUID();
    Document orphan = indexedDocument(vanishedLibraryId, "scan.pdf", 1L, 0);

    when(documentRepository.findByStatusAndChunkCountLessThanEqual(DocumentStatus.INDEXED, 0))
        .thenReturn(List.of(orphan));
    when(libraryRepository.findAllById(containingExactly(vanishedLibraryId))).thenReturn(List.of());

    List<LowChunkDocumentAuditService.LibraryLowChunkReport> reports =
        service.findLowChunkDocuments(0);

    assertThat(reports).hasSize(1);
    assertThat(reports.getFirst().libraryName()).isEqualTo("Unbekannte Bibliothek");
  }

  @Test
  void thresholdIsPassedThroughToTheRepositoryQuery() {
    when(documentRepository.findByStatusAndChunkCountLessThanEqual(DocumentStatus.INDEXED, 3))
        .thenReturn(List.of());

    service.findLowChunkDocuments(3);

    verify(documentRepository).findByStatusAndChunkCountLessThanEqual(DocumentStatus.INDEXED, 3);
  }
}
