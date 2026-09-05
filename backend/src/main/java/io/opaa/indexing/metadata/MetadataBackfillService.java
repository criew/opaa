package io.opaa.indexing.metadata;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentBatchLoop;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.StoredDocumentSourceAccess;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * is re-read from its original through {@link DocumentMetadataService#reextractFromFile} - no
 * re-chunking, no embedding, no model call.
 *
 * <p>Resumable by construction, like {@link io.opaa.indexing.PipelineReindexService}: the remaining
 * work is re-derived on every call, and an unadvanceable document is scanned past by offset.
 * Pausing is not calling again. A system process without a person's rights context, never
 * scheduled.
 */
@Service
public class MetadataBackfillService {

  private static final Logger log = LoggerFactory.getLogger(MetadataBackfillService.class);

  private final JdbcTemplate jdbcTemplate;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentMetadataService documentMetadataService;
  private final StoredDocumentSourceAccess sourceAccess;
  private final ChecksumService checksumService;
  private final MetadataFillCounter fillCounter;

  /** Skipped count of the most recent call per library; process lifetime only (ADR-0021). */
  private final Map<UUID, Integer> lastSkippedByLibrary = new ConcurrentHashMap<>();

  public MetadataBackfillService(
      JdbcTemplate jdbcTemplate,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      DocumentMetadataService documentMetadataService,
      StoredDocumentSourceAccess sourceAccess,
      ChecksumService checksumService,
      MetadataFillCounter fillCounter) {
    this.jdbcTemplate = jdbcTemplate;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.documentMetadataService = documentMetadataService;
    this.sourceAccess = sourceAccess;
    this.checksumService = checksumService;
    this.fillCounter = fillCounter;
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
    Map<Advance, Integer> counts =
        DocumentBatchLoop.run(
            batchSize,
            Advance.class,
            Advance.SKIPPED,
            (limit, offset) -> selectPendingDocuments(library.getId(), limit, offset),
            this::advance);
    int skipped = counts.get(Advance.SKIPPED);
    lastSkippedByLibrary.put(library.getId(), skipped);
    return new MetadataBackfillResult(
        counts.get(Advance.PROCESSED), counts.get(Advance.MARKED_FOR_NEXT_RUN), skipped);
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
      // The chunks were cut from the bytes read at indexing time. A file replaced since then would
      // put the core fields of a different text onto those chunks - the same rule the attachment
      // path applies; the next connector run re-indexes it and extracts along the way.
      if (document.getChecksum() != null
          && !checksumService.computeSha256(localFile).equals(document.getChecksum())) {
        log.info(
            "Skipping document {} in the metadata backfill: its file changed since indexing",
            documentId);
        return Advance.SKIPPED;
      }
      documentMetadataService.reextractFromFile(document, localFile);
      return Advance.PROCESSED;
    } catch (RuntimeException | IOException e) {
      log.warn(
          "Skipping document {} in the metadata backfill: re-extraction failed", documentId, e);
      return Advance.SKIPPED;
    }
  }

  /**
   * An RSS entry's own body was never a file: its declared properties are the stored headline and
   * the feed's publication instant, exactly what the ingest hands the extraction, so it is re-run
   * from the row without a download. "Has a headline" is approximated as {@code file_name !=
   * file_path}. Everything else remote can only be re-read by its own connector run and is marked
   * for it. The name is marked synthetic exactly as the ingest marks it.
   */
  private Advance advanceRemote(Document document) {
    if (document.getSourceType() == DocumentSourceType.RSS_FEED
        && document.getParentDocumentId() == null) {
      boolean hasHeadline =
          document.getFileName() != null && !document.getFileName().equals(document.getFilePath());
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withTitle(hasHeadline ? document.getFileName() : null)
              .withSyntheticName(true)
              .withDocumentDate(
                  DocumentProperties.instantToLocalDate(document.getLastModifiedRemote()));
      documentMetadataService.reextractFromProperties(document, properties);
      return Advance.PROCESSED;
    }
    return sourceAccess.markRemoteChainForNextRun(document)
        ? Advance.MARKED_FOR_NEXT_RUN
        : Advance.SKIPPED;
  }

  /** A document below the current extraction version. */
  private static String pendingSql(String alias) {
    return "("
        + alias
        + "metadata_extraction_version IS NULL OR "
        + alias
        + "metadata_extraction_version < ?)";
  }

  /**
   * A pending document a backfill call can still advance: a local file, an RSS entry body, or a
   * remote document not yet marked for its next connector run. That last leg relies on {@link
   * DocumentRepository#markForReindexOnNextRun} clearing {@code checksum} - excluding an
   * already-marked document is what lets the run drain instead of re-marking the same rows.
   */
  private static String advanceableSql(String alias) {
    return "("
        + alias
        + "source_type IN ('FILESYSTEM', 'UPLOAD') OR ("
        + alias
        + "source_type = 'RSS_FEED' AND "
        + alias
        + "parent_document_id IS NULL) OR "
        + alias
        + "checksum IS NOT NULL)";
  }

  /**
   * Advanceable pending documents in stable id order, so the offset scans past what this call
   * already found unadvanceable.
   */
  private List<UUID> selectPendingDocuments(UUID libraryId, int limit, int offset) {
    return jdbcTemplate.query(
        "SELECT id FROM documents WHERE library_id = ? AND status = ? AND "
            + pendingSql("")
            + " AND "
            + advanceableSql("")
            + " ORDER BY id OFFSET ? LIMIT ?",
        (rs, i) -> (UUID) rs.getObject("id"),
        libraryId,
        DocumentStatus.INDEXED.name(),
        CoreMetadataExtractor.EXTRACTION_VERSION,
        offset,
        limit);
  }

  /**
   * The extraction state of every library in {@code libraryIds}: the version counts from one
   * grouped query over the {@code INDEXED} documents, the per-field Füllstand from the one counter
   * the Pflege-Anker shares ({@link MetadataFillCounter},). A library without indexed documents is
   * absent from the result.
   */
  public Map<UUID, MetadataBackfillProgress> progressForLibraries(Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return Map.of();
    }
    List<String> fieldKeys =
        Arrays.stream(CoreMetadataField.values()).map(CoreMetadataField::key).toList();
    Map<UUID, Map<String, MetadataFieldFill>> fills = fillCounter.countFor(libraryIds, fieldKeys);

    Map<UUID, MetadataBackfillProgress> byLibrary = new HashMap<>();
    jdbcTemplate.query(
        "SELECT d.library_id, count(*) AS total, count(*) FILTER (WHERE"
            + " d.metadata_extraction_version >= ?) AS current_count, count(*) FILTER (WHERE "
            + pendingSql("d.")
            + ") AS pending_count, count(*) FILTER (WHERE "
            + pendingSql("d.")
            + " AND NOT "
            + advanceableSql("d.")
            + ") AS awaiting_count FROM documents d WHERE d.status = ? AND d.library_id IN ("
            + libraryIds.stream().map(id -> "?").collect(Collectors.joining(", "))
            + ") GROUP BY d.library_id",
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          Map<String, MetadataFieldFill> byKey = fills.getOrDefault(libraryId, Map.of());
          Map<CoreMetadataField, MetadataFieldFill> byField =
              new EnumMap<>(CoreMetadataField.class);
          for (CoreMetadataField field : CoreMetadataField.values()) {
            byField.put(field, byKey.getOrDefault(field.key(), MetadataFieldFill.EMPTY));
          }
          byLibrary.put(
              libraryId,
              new MetadataBackfillProgress(
                  libraryId,
                  rs.getLong("total"),
                  rs.getLong("current_count"),
                  rs.getLong("pending_count"),
                  rs.getLong("awaiting_count"),
                  lastSkippedByLibrary.getOrDefault(libraryId, 0),
                  byField));
        },
        parameters(libraryIds));
    return byLibrary;
  }

  /** The three extraction-version binds, then the status, then one bind per library. */
  private static Object[] parameters(Collection<UUID> libraryIds) {
    List<Object> parameters = new ArrayList<>();
    parameters.add(CoreMetadataExtractor.EXTRACTION_VERSION);
    parameters.add(CoreMetadataExtractor.EXTRACTION_VERSION);
    parameters.add(CoreMetadataExtractor.EXTRACTION_VERSION);
    parameters.add(DocumentStatus.INDEXED.name());
    parameters.addAll(libraryIds);
    return parameters.toArray();
  }
}
