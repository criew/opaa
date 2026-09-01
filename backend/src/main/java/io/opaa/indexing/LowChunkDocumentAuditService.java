package io.opaa.indexing;

import io.opaa.api.types.DocumentStatus;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The one-time inventory check from ingestion-pipelines.md, Teil 3, Punkt 1 "Scan-Erkennung und
 * Bestandsprüfung": the scan detection {@link DocumentService#isTextlessPdf} adds only works going
 * forward - a PDF already indexed with null or auffällig wenigen chunks before that check existed
 * stays in the bestand unless something else finds it. {@link #findLowChunkDocuments} answers
 * "which of the current bestand's INDEXED documents are actually empty or near-empty", grouped per
 * library with filename, file size and chunk count, so an operator can decide what to do with each
 * one.
 *
 * <p>Deliberately a plain query, not a scheduled job or a stored snapshot: whether an INDEXED
 * document has too few chunks is itself a live property of {@code documents.chunk_count} - calling
 * this method again always answers against the bestand's current state, which is what "die Kennzahl
 * bleibt dauerhaft ... abfragbar" requires, without a separate audit table that could drift from
 * reality.
 */
public class LowChunkDocumentAuditService {

  /**
   * The default threshold {@link #findLowChunkDocuments} is called with when a caller has no
   * stronger opinion: only the unambiguous anomaly ("null Chunks") this issue's own bug produces.
   * "auffällig wenige" beyond that has no measured threshold yet (no benchmark covers it, see
   * ingestion-pipelines.md's own "gesetzt, nicht gemessen" rule for chunk sizes) - an operator who
   * wants to catch near-empty documents too raises {@code chunkCountThreshold} explicitly.
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
   * Every {@link DocumentStatus#INDEXED} document whose {@code chunkCount} is at or below {@code
   * chunkCountThreshold}, grouped per library and sorted by library name, then file name within a
   * library. A library with no such document is simply absent from the result, never present with
   * an empty list.
   */
  public List<LibraryLowChunkReport> findLowChunkDocuments(int chunkCountThreshold) {
    List<Document> flagged =
        documentRepository.findByStatusAndChunkCountLessThanEqual(
            DocumentStatus.INDEXED, chunkCountThreshold);
    if (flagged.isEmpty()) {
      return List.of();
    }

    Map<UUID, List<Document>> byLibrary =
        flagged.stream()
            .collect(
                Collectors.groupingBy(
                    Document::getLibraryId, LinkedHashMap::new, Collectors.toList()));
    Map<UUID, String> libraryNames =
        libraryRepository.findAllById(byLibrary.keySet()).stream()
            .collect(Collectors.toMap(KnowledgeLibrary::getId, KnowledgeLibrary::getName));

    return byLibrary.entrySet().stream()
        .map(entry -> toReport(entry.getKey(), libraryNames, entry.getValue()))
        .sorted(Comparator.comparing(LibraryLowChunkReport::libraryName))
        .toList();
  }

  private LibraryLowChunkReport toReport(
      UUID libraryId, Map<UUID, String> libraryNames, List<Document> documents) {
    // A library deleted between the query above and this mapping (or, in principle, a document
    // whose library row no longer resolves) still gets its documents reported, under a name that
    // says so rather than silently vanishing from the report.
    String libraryName = libraryNames.getOrDefault(libraryId, "Unbekannte Bibliothek");
    List<LowChunkDocumentEntry> entries =
        documents.stream()
            .map(
                d ->
                    new LowChunkDocumentEntry(
                        d.getId(), d.getFileName(), d.getFileSize(), d.getChunkCount()))
            .sorted(Comparator.comparing(LowChunkDocumentEntry::fileName))
            .toList();
    return new LibraryLowChunkReport(libraryId, libraryName, entries);
  }

  /** One flagged document within {@link LibraryLowChunkReport#documents()}. */
  public record LowChunkDocumentEntry(
      UUID documentId, String fileName, Long fileSize, int chunkCount) {}

  /** A single library's own share of {@link #findLowChunkDocuments}'s result. */
  public record LibraryLowChunkReport(
      UUID libraryId, String libraryName, List<LowChunkDocumentEntry> documents) {}
}
