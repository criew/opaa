package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit coverage of the one-time inventory check from ingestion-pipelines.md, Teil 3, Punkt 1
 * "Scan-Erkennung und Bestandsprüfung" (#1055/#1090): finding already-INDEXED documents of one
 * organization with null or auffällig wenigen Chunks, paged and carrying their library's name.
 */
class LowChunkDocumentAuditServiceTest {

  private DocumentRepository documentRepository;
  private KnowledgeLibraryRepository libraryRepository;
  private LowChunkDocumentAuditService service;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    documentRepository = mock(DocumentRepository.class);
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    service = new LowChunkDocumentAuditService(documentRepository, libraryRepository);
    organizationId = UUID.randomUUID();
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

  @Test
  void returnsAnEmptyPageWhenNoDocumentIsAtOrBelowTheThreshold() {
    Pageable pageable = PageRequest.of(0, 20);
    when(documentRepository.findByOrganizationIdAndStatusAndChunkCountLessThanEqual(
            organizationId, DocumentStatus.INDEXED, 0, pageable))
        .thenReturn(Page.empty(pageable));

    Page<LowChunkDocumentAuditService.LowChunkDocumentEntry> result =
        service.findLowChunkDocuments(organizationId, 0, pageable);

    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void scopesToTheCallersOrganizationAndCarriesLibraryNameFileNameSizeAndChunkCount() {
    KnowledgeLibrary library = library("Satzungen");
    Document zeroChunk = indexedDocument(library.getId(), "scan.pdf", 12_345L, 0);
    Pageable pageable = PageRequest.of(0, 20);

    when(documentRepository.findByOrganizationIdAndStatusAndChunkCountLessThanEqual(
            organizationId, DocumentStatus.INDEXED, 0, pageable))
        .thenReturn(new PageImpl<>(List.of(zeroChunk), pageable, 1));
    when(libraryRepository.findAllById(Set.of(library.getId()))).thenReturn(List.of(library));

    Page<LowChunkDocumentAuditService.LowChunkDocumentEntry> result =
        service.findLowChunkDocuments(organizationId, 0, pageable);

    assertThat(result.getTotalElements()).isEqualTo(1);
    LowChunkDocumentAuditService.LowChunkDocumentEntry entry = result.getContent().getFirst();
    assertThat(entry.libraryId()).isEqualTo(library.getId());
    assertThat(entry.libraryName()).isEqualTo("Satzungen");
    assertThat(entry.fileName()).isEqualTo("scan.pdf");
    assertThat(entry.fileSize()).isEqualTo(12_345L);
    assertThat(entry.chunkCount()).isEqualTo(0);
  }

  @Test
  void aLibraryThatNoLongerResolvesStillReportsItsDocumentUnderAPlaceholderName() {
    UUID vanishedLibraryId = UUID.randomUUID();
    Document orphan = indexedDocument(vanishedLibraryId, "scan.pdf", 1L, 0);
    Pageable pageable = PageRequest.of(0, 20);

    when(documentRepository.findByOrganizationIdAndStatusAndChunkCountLessThanEqual(
            organizationId, DocumentStatus.INDEXED, 0, pageable))
        .thenReturn(new PageImpl<>(List.of(orphan), pageable, 1));
    when(libraryRepository.findAllById(Set.of(vanishedLibraryId))).thenReturn(List.of());

    Page<LowChunkDocumentAuditService.LowChunkDocumentEntry> result =
        service.findLowChunkDocuments(organizationId, 0, pageable);

    assertThat(result.getContent().getFirst().libraryName()).isEqualTo("Unbekannte Bibliothek");
  }

  @Test
  void thresholdAndPageableArePassedThroughToTheRepositoryQuery() {
    Pageable pageable = PageRequest.of(2, 10);
    when(documentRepository.findByOrganizationIdAndStatusAndChunkCountLessThanEqual(
            organizationId, DocumentStatus.INDEXED, 3, pageable))
        .thenReturn(Page.empty(pageable));

    service.findLowChunkDocuments(organizationId, 3, pageable);

    verify(documentRepository)
        .findByOrganizationIdAndStatusAndChunkCountLessThanEqual(
            eq(organizationId), eq(DocumentStatus.INDEXED), eq(3), eq(pageable));
  }
}
