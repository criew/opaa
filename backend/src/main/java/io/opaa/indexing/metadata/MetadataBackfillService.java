package io.opaa.indexing.metadata;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.StoredDocumentSourceAccess;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The deterministic core-metadata backfill over a library's Altbestand (metadata-schema.md,
 * "Deterministischer Bestandslauf"; ADR-0024): every {@code INDEXED} document whose {@code
 * metadata_extraction_version} is missing or below {@link CoreMetadataExtractor#EXTRACTION_VERSION}
 * is re-read from its original file through {@link DocumentMetadataService#reextractFromFile} - no
 * re-chunking, no embedding, no model call.
 *
 * <p>Resumable by construction, like {@link io.opaa.indexing.PipelineReindexService}: the remaining
 * work is re-derived from the {@code documents} table on every call, a processed document drops out
 * of the selection by its recorded version, an unadvanceable one is scanned past by offset and
 * retried next call. Pausing is not calling again. A system process without a person's rights
 * context (Epic #1065, Beschluss 1); never scheduled, only driven by {@code
 * IndexingAdminController}.
 */
@Service
public class MetadataBackfillService {

  private static final Logger log = LoggerFactory.getLogger(MetadataBackfillService.class);

  /**
   * Same bound as {@code PipelineReindexService}: how far past skipped candidates one call scans.
   */
  private static final int MAX_SKIP_SCAN_FACTOR = 10;

  private final JdbcTemplate jdbcTemplate;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentMetadataService documentMetadataService;
  private final StoredDocumentSourceAccess sourceAccess;

  /** Skipped count of the most recent call per library; process lifetime only (ADR-0021). */
  private final Map<UUID, Integer> lastSkippedByLibrary = new ConcurrentHashMap<>();

  public MetadataBackfillService(
      JdbcTemplate jdbcTemplate,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      DocumentMetadataService documentMetadataService,
      StoredDocumentSourceAccess sourceAccess) {
    this.jdbcTemplate = jdbcTemplate;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.documentMetadataService = documentMetadataService;
    this.sourceAccess = sourceAccess;
  }

  /**
   * Advances up to {@code batchSize} pending documents of {@code libraryId}, which must belong to
   * {@code organizationId} (a foreign library is absent, not forbidden: 404). Call repeatedly until
   * the result {@link MetadataBackfillResult#isEmpty() is empty}. Deliberately not
   * {@code @Transactional}: each document commits on its own inside {@link
   * DocumentMetadataService}, so an interrupted batch keeps every document it finished.
   */
  public MetadataBackfillResult backfillBatch(UUID organizationId, UUID libraryId, int batchSize) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> organizationId.equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    if (batchSize <= 0) {
      return MetadataBackfillResult.NOTHING_TO_DO;
    }
    int processed = 0;
    int marked = 0;
    int skipped = 0;
    int maxSkips = batchSize * MAX_SKIP_SCAN_FACTOR;

    while (processed + marked < batchSize && skipped < maxSkips) {
      List<UUID> candidates = selectPendingDocuments(library.getId(), batchSize, skipped);
      if (candidates.isEmpty()) {
        break;
      }
      boolean exhausted = true;
      for (UUID documentId : candidates) {
        if (processed + marked >= batchSize) {
          exhausted = false;
          break;
        }
        switch (advance(documentId)) {
          case PROCESSED -> processed++;
          case MARKED_FOR_NEXT_RUN -> marked++;
          case SKIPPED -> skipped++;
        }
        if (skipped >= maxSkips) {
          exhausted = false;
          break;
        }
      }
      if (exhausted && candidates.size() < batchSize) {
        break;
      }
    }
    lastSkippedByLibrary.put(library.getId(), skipped);
    return new MetadataBackfillResult(processed, marked, skipped);
  }

  private enum Advance {
    PROCESSED,
    MARKED_FOR_NEXT_RUN,
    SKIPPED
  }

  /**
   * One document. Every failure costs only this candidate: it is logged, counted as skipped and
   * left exactly as it was - {@link DocumentMetadataService} commits values, chunk keys and the
   * extraction version together or not at all.
   */
  private Advance advance(UUID documentId) {
    Document document = documentRepository.findById(documentId).orElse(null);
    if (document == null) {
      return Advance.SKIPPED;
    }
    try {
      if (StoredDocumentSourceAccess.isRemote(document)) {
        return advanceRemote(document);
      }
      if (document.getParentDocumentId() != null) {
        boolean advanced =
            sourceAccess.withReextractedAttachment(
                document,
                file -> {
                  documentMetadataService.reextractFromFile(document, file);
                  return true;
                });
        return advanced ? Advance.PROCESSED : Advance.SKIPPED;
      }
      Path localFile = sourceAccess.localSourceFile(document);
      if (localFile == null) {
        log.info(
            "Skipping document {} in the metadata backfill: its file is not readable within the"
                + " directories this deployment is configured to read",
            documentId);
        return Advance.SKIPPED;
      }
      documentMetadataService.reextractFromFile(document, localFile);
      return Advance.PROCESSED;
    } catch (RuntimeException e) {
      log.warn(
          "Skipping document {} in the metadata backfill: re-extraction failed", documentId, e);
      return Advance.SKIPPED;
    }
  }

  /**
   * An RSS entry's own body was never a file: its declared properties are the stored headline
   * ({@code file_name}, unless that is only the URL) and the feed's publication instant ({@code
   * last_modified_remote}) - exactly what {@code FileProcessingService#processRssEntry} hands the
   * extraction, so it is re-run from the row without a download. Everything else remote (an HTTP
   * directory file, any remote attachment) can only be re-read by its own connector run, which
   * re-extracts on every inflow, and is marked for it.
   */
  private Advance advanceRemote(Document document) {
    if (document.getSourceType() == DocumentSourceType.RSS_FEED
        && document.getParentDocumentId() == null) {
      boolean hasHeadline =
          document.getFileName() != null && !document.getFileName().equals(document.getFilePath());
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withTitle(hasHeadline ? document.getFileName() : null)
              .withDocumentDate(publishedDate(document.getLastModifiedRemote()));
      documentMetadataService.reextractFromProperties(document, properties);
      return Advance.PROCESSED;
    }
    return sourceAccess.markRemoteChainForNextRun(document)
        ? Advance.MARKED_FOR_NEXT_RUN
        : Advance.SKIPPED;
  }

  private static java.time.LocalDate publishedDate(String publishedAt) {
    if (publishedAt == null || publishedAt.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(publishedAt).atZone(ZoneOffset.UTC).toLocalDate();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Pending documents in stable id order, so the offset scans past what this call already found
   * unadvanceable. A remote document already marked for its next run (both change markers cleared)
   * has had everything done to it this run can do and is excluded, so the run drains.
   */
  private List<UUID> selectPendingDocuments(UUID libraryId, int limit, int offset) {
    return jdbcTemplate.query(
        "SELECT id FROM documents WHERE library_id = ? AND status = ? AND ("
            + "metadata_extraction_version IS NULL OR metadata_extraction_version < ?) AND ("
            + "source_type IN ('FILESYSTEM', 'UPLOAD') OR (source_type = 'RSS_FEED' AND"
            + " parent_document_id IS NULL) OR checksum IS NOT NULL) ORDER BY id OFFSET ? LIMIT ?",
        (rs, i) -> (UUID) rs.getObject("id"),
        libraryId,
        DocumentStatus.INDEXED.name(),
        CoreMetadataExtractor.EXTRACTION_VERSION,
        offset,
        limit);
  }

  /**
   * The extraction state of every library in {@code libraryIds}, from one grouped query over the
   * {@code INDEXED} documents joined to their core-field rows (one row per document and field, so
   * the joins never multiply). A library without indexed documents is absent from the result.
   */
  public Map<UUID, MetadataBackfillProgress> progressForLibraries(Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return Map.of();
    }
    CoreMetadataField[] fields = CoreMetadataField.values();
    StringBuilder sql =
        new StringBuilder(
            "SELECT d.library_id, count(*) AS total, count(*) FILTER (WHERE"
                + " d.metadata_extraction_version >= ?) AS current_count, count(*) FILTER (WHERE"
                + " d.metadata_extraction_version IS NULL OR d.metadata_extraction_version < ?)"
                + " AS pending_count");
    List<Object> params = new ArrayList<>();
    params.add(CoreMetadataExtractor.EXTRACTION_VERSION);
    params.add(CoreMetadataExtractor.EXTRACTION_VERSION);
    for (int i = 0; i < fields.length; i++) {
      sql.append(", count(f").append(i).append(".document_id) AS filled_").append(i);
    }
    sql.append(" FROM documents d");
    for (int i = 0; i < fields.length; i++) {
      sql.append(" LEFT JOIN document_metadata_values f")
          .append(i)
          .append(" ON f")
          .append(i)
          .append(".document_id = d.id AND f")
          .append(i)
          .append(".field_key = ? AND f")
          .append(i)
          .append(".value_state = 'SET'");
      params.add(fields[i].key());
    }
    sql.append(" WHERE d.status = ? AND d.library_id IN (")
        .append(libraryIds.stream().map(id -> "?").collect(Collectors.joining(", ")))
        .append(") GROUP BY d.library_id");
    params.add(DocumentStatus.INDEXED.name());
    params.addAll(libraryIds);

    Map<UUID, MetadataBackfillProgress> byLibrary = new HashMap<>();
    jdbcTemplate.query(
        sql.toString(),
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          Map<CoreMetadataField, Long> filled = new EnumMap<>(CoreMetadataField.class);
          for (int i = 0; i < fields.length; i++) {
            filled.put(fields[i], rs.getLong("filled_" + i));
          }
          byLibrary.put(
              libraryId,
              new MetadataBackfillProgress(
                  libraryId,
                  rs.getLong("total"),
                  rs.getLong("current_count"),
                  rs.getLong("pending_count"),
                  lastSkippedByLibrary.getOrDefault(libraryId, 0),
                  filled));
        },
        params.toArray());
    return byLibrary;
  }
}
