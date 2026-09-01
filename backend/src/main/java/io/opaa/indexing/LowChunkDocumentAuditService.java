package io.opaa.indexing;

import io.opaa.api.types.DocumentStatus;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-time inventory check for scan-PDF fallout (ingestion-pipelines.md, Teil 3, Punkt 1):
 * {@link DocumentService#isTextlessPdf} only guards new ingests, so a pre-fix document indexed with
 * too few chunks stays undetected otherwise. {@link #findLowChunkDocuments} is a live query over
 * {@code documents.chunk_count}, not a stored snapshot - always current, no audit table to drift
 * from reality.
 */
public class LowChunkDocumentAuditService {

  /**
   * Default threshold: only the unambiguous zero-chunk anomaly. A higher, unmeasured "auffällig
   * wenige" threshold is left to the caller (see ingestion-pipelines.md's own "gesetzt, nicht
   * gemessen" rule for unmeasured chunk-size values).
   */
  public static final int DEFAULT_CHUNK_COUNT_THRESHOLD = 0;

  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;

  public LowChunkDocumentAuditService(
      DocumentRepository documentRepository, KnowledgeLibraryRepository libraryRepository) {
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
  }

  /**
   * One page of {@code organizationId}'s {@link DocumentStatus#INDEXED} documents whose {@code
   * chunkCount} is at or below {@code chunkCountThreshold}, each carrying its library's name.
   */
  @Transactional(readOnly = true)
  public Page<LowChunkDocumentEntry> findLowChunkDocuments(
      UUID organizationId, int chunkCountThreshold, Pageable pageable) {
    Page<Document> page =
        documentRepository.findByOrganizationIdAndStatusAndChunkCountLessThanEqual(
            organizationId, DocumentStatus.INDEXED, chunkCountThreshold, pageable);
    if (page.isEmpty()) {
      return Page.empty(pageable);
    }

    Set<UUID> libraryIds =
        page.getContent().stream().map(Document::getLibraryId).collect(Collectors.toSet());
    Map<UUID, String> libraryNames =
        libraryRepository.findAllById(libraryIds).stream()
            .collect(Collectors.toMap(KnowledgeLibrary::getId, KnowledgeLibrary::getName));

    return page.map(
        d ->
            new LowChunkDocumentEntry(
                d.getId(),
                d.getLibraryId(),
                // A library deleted between the query above and this mapping still gets its
                // document reported, under a name that says so rather than silently vanishing.
                libraryNames.getOrDefault(d.getLibraryId(), "Unbekannte Bibliothek"),
                d.getFileName(),
                d.getFileSize(),
                d.getChunkCount()));
  }

  /** One flagged document in {@link #findLowChunkDocuments}'s result page. */
  public record LowChunkDocumentEntry(
      UUID documentId,
      UUID libraryId,
      String libraryName,
      String fileName,
      Long fileSize,
      int chunkCount) {}
}
