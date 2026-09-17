package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataSchemaChangeKind;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.maintenance.DocumentBatchLoop;
import io.opaa.library.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Nachlauf of a retired list value and of a retired field (metadata-schema.md, "Nachlauf im
 * Betrieb", #1361). It runs on the same chargen loop as the Bestandslauf and the Kontextpräfix
 * rerun ({@link DocumentBatchLoop}); its selection is the {@link LibraryMetadataSchemaChange} row,
 * and its processing unit is one document in one transaction with its own audit event.
 *
 * <p><b>The invalid state stays unreachable the whole run.</b> A retired value remains listed and
 * filterable until the last document has left it, and it is what no new document may be given; the
 * list entry - respectively the field - is dropped only in the same transaction that finds no
 * document carrying it any more, under a row lock that a concurrent write would have to take too.
 * The {@code ON DELETE RESTRICT} of the document rows is the second guard beneath that.
 *
 * <p>Resumable and idempotent by construction: an advanced document leaves the selection, a
 * document that cannot be advanced keeps everything it had and stays pending, and pausing is not
 * calling again.
 */
@Service
public class LibraryMetadataSchemaChangeService {

  /** Documents one Charge rewrites at most; also the default of the continuation endpoint. */
  public static final int DEFAULT_BATCH_SIZE = 500;

  static final String REMAP_CORRELATION_PREFIX = "metadata-remap-";
  static final String DELETE_CORRELATION_PREFIX = "metadata-field-delete-";

  private static final Logger log =
      LoggerFactory.getLogger(LibraryMetadataSchemaChangeService.class);

  private final LibraryMetadataSchemaChangeRepository changeRepository;
  private final LibraryMetadataFieldRepository fieldRepository;
  private final LibraryMetadataFieldValueRepository valueRepository;
  private final DocumentMetadataValueRepository documentValueRepository;
  private final DocumentRepository documentRepository;
  private final DocumentMetadataService metadataService;
  private final DocumentMetadataCorrectionService correctionService;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  /** Skipped count of the most recent Charge per library; process lifetime only (ADR-0021). */
  private final Map<UUID, Long> lastSkippedByLibrary = new ConcurrentHashMap<>();

  public LibraryMetadataSchemaChangeService(
      LibraryMetadataSchemaChangeRepository changeRepository,
      LibraryMetadataFieldRepository fieldRepository,
      LibraryMetadataFieldValueRepository valueRepository,
      DocumentMetadataValueRepository documentValueRepository,
      DocumentRepository documentRepository,
      DocumentMetadataService metadataService,
      DocumentMetadataCorrectionService correctionService,
      DocumentTypeVocabularyRepository vocabularyRepository,
      ApplicationEventPublisher eventPublisher,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this.changeRepository = changeRepository;
    this.fieldRepository = fieldRepository;
    this.valueRepository = valueRepository;
    this.documentValueRepository = documentValueRepository;
    this.documentRepository = documentRepository;
    this.metadataService = metadataService;
    this.correctionService = correctionService;
    this.vocabularyRepository = vocabularyRepository;
    this.eventPublisher = eventPublisher;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Retires {@code value} and hands its documents to the run; the list entry is removed by the run,
   * never here. Committed on its own, before the first Charge: the retirement is what keeps a new
   * document from being given the value while the run is under way.
   */
  @Transactional
  LibraryMetadataSchemaChange retireValue(
      LibraryMetadataFieldValue value, LibraryMetadataFieldValue target) {
    return changeRepository.save(
        LibraryMetadataSchemaChange.valueRemap(
            value.getFieldId(),
            value.getId(),
            target == null ? null : target.getId(),
            REMAP_CORRELATION_PREFIX + UUID.randomUUID()));
  }

  /** Retires {@code field}; the field row itself is removed by the run, never here. */
  @Transactional
  LibraryMetadataSchemaChange retireField(LibraryMetadataField field) {
    return changeRepository.save(
        LibraryMetadataSchemaChange.fieldDeletion(
            field.getId(), DELETE_CORRELATION_PREFIX + UUID.randomUUID()));
  }

  /**
   * One Charge over every pending change of {@code library}, oldest change first. Call repeatedly
   * until the result is {@link LibraryMetadataSchemaRunResult#complete() complete}; pausing is not
   * calling again. Deliberately not {@code @Transactional}: the whole point is that each document
   * commits on its own, so an interrupted run keeps what it has done.
   */
  public LibraryMetadataSchemaRunResult runBatch(
      KnowledgeLibrary library, int batchSize, CurrentUser caller) {
    return run(library, changeRepository.findByLibraryId(library.getId()), batchSize, caller);
  }

  /** One Charge over exactly {@code change} - the first one, run by the confirming call itself. */
  public LibraryMetadataSchemaRunResult runBatch(
      KnowledgeLibrary library,
      LibraryMetadataSchemaChange change,
      int batchSize,
      CurrentUser caller) {
    return run(library, List.of(change), batchSize, caller);
  }

  private LibraryMetadataSchemaRunResult run(
      KnowledgeLibrary library,
      List<LibraryMetadataSchemaChange> changes,
      int batchSize,
      CurrentUser caller) {
    if (changes.isEmpty()) {
      lastSkippedByLibrary.remove(library.getId());
      return LibraryMetadataSchemaRunResult.nothingToDo();
    }
    long processed = 0;
    long skipped = 0;
    int budget = Math.max(batchSize, 1);
    boolean advancedAnything = false;
    for (LibraryMetadataSchemaChange change : changes) {
      if (budget <= 0) {
        break;
      }
      ChargeOutcome outcome = advance(library, change, budget, caller);
      processed += outcome.processed();
      skipped += outcome.skipped();
      budget -= (int) outcome.processed();
      advancedAnything |= outcome.processed() > 0 || outcome.completed();
    }
    if (skipped == 0) {
      lastSkippedByLibrary.remove(library.getId());
    } else {
      lastSkippedByLibrary.put(library.getId(), skipped);
    }
    if (advancedAnything) {
      // The offered filter values are derived from the bestand, so every rewritten document
      // changes them - and a completed change removed a value the cache would still offer.
      eventPublisher.publishEvent(new LibraryMetadataSchemaChanged(library.getId()));
    }
    return new LibraryMetadataSchemaRunResult(processed, skipped, pendingChanges(library.getId()));
  }

  /** What one Charge of a single change did. */
  private record ChargeOutcome(long processed, long skipped, boolean completed) {}

  private ChargeOutcome advance(
      KnowledgeLibrary library,
      LibraryMetadataSchemaChange change,
      int budget,
      CurrentUser caller) {
    LibraryMetadataField field = fieldRepository.findById(change.getFieldId()).orElse(null);
    if (field == null) {
      // The field was taken by its library's deletion; the change has lost its subject.
      transactionTemplate.executeWithoutResult(status -> changeRepository.delete(change));
      return new ChargeOutcome(0, 0, true);
    }
    MetadataFieldRef ref = MetadataFieldRef.of(field);
    MetadataValueInput replacement =
        change.getTargetValueId() == null
            ? null
            : valueRepository
                .findById(change.getTargetValueId())
                .map(target -> MetadataValueInput.libraryValue(target.getCode(), target.getId()))
                .orElse(null);
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    Map<Advance, Integer> counts =
        DocumentBatchLoop.run(
            budget,
            Advance.class,
            Advance.SKIPPED,
            (limit, offset) -> selectDocuments(change, limit, offset),
            documentId ->
                advanceDocument(library, change, ref, replacement, vocabulary, caller, documentId));
    long processed = counts.get(Advance.REWRITTEN) + counts.get(Advance.ALREADY_DONE);
    return new ChargeOutcome(processed, counts.get(Advance.SKIPPED), completeIfDrained(change));
  }

  /**
   * The outcomes of one document. {@code ALREADY_DONE} is progress without work - a concurrent
   * Charge got there first - and must not be scanned past like a skip, or the offset would step
   * over a document that still needs rewriting.
   */
  private enum Advance {
    REWRITTEN,
    ALREADY_DONE,
    SKIPPED
  }

  /**
   * One document in one transaction: its row is locked first, so exactly one concurrent Charge
   * rewrites it and writes its audit event. Every failure costs only this candidate - it is logged,
   * counted as skipped and left exactly as it was, still carrying a value the schema lists.
   */
  private Advance advanceDocument(
      KnowledgeLibrary library,
      LibraryMetadataSchemaChange change,
      MetadataFieldRef ref,
      MetadataValueInput replacement,
      DocumentTypeVocabulary vocabulary,
      CurrentUser caller,
      UUID documentId) {
    try {
      return transactionTemplate.execute(
          status -> {
            if (!lockCarriedValue(change, documentId)) {
              return Advance.ALREADY_DONE;
            }
            Document document = documentRepository.findById(documentId).orElse(null);
            if (document == null) {
              return Advance.ALREADY_DONE;
            }
            ManualValueChange valueChange =
                replacement == null
                    ? metadataService.deleteValue(documentId, ref)
                    : metadataService.setManualValue(documentId, ref, replacement, caller.id());
            if (valueChange.changed()) {
              correctionService.recordChange(
                  library,
                  document,
                  ref,
                  valueChange,
                  caller,
                  change.getCorrelationRef(),
                  vocabulary);
            }
            changeRepository
                .findById(change.getId())
                .ifPresent(LibraryMetadataSchemaChange::countProcessedDocument);
            return Advance.REWRITTEN;
          });
    } catch (RuntimeException e) {
      log.warn(
          "Skipping document {} of schema change {}: rewriting its value failed",
          documentId,
          change.getId(),
          e);
      return Advance.SKIPPED;
    }
  }

  /**
   * Removes the retired list entry - respectively the retired field with its list - once no
   * document carries it any more, and reports whether it did. The subject row is locked before it
   * is counted: a concurrent write of that very value has to take a conflicting lock for its
   * foreign key, so it either lands before the count (and postpones the removal) or after the
   * removal (and fails on the foreign key). Neither can leave a document with a value the schema no
   * longer lists.
   */
  private boolean completeIfDrained(LibraryMetadataSchemaChange change) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(
            status -> {
              if (!lockSubject(change)) {
                changeRepository.deleteById(change.getId());
                return true;
              }
              long remaining = remainingDocuments(change);
              if (remaining > 0) {
                return false;
              }
              changeRepository.deleteById(change.getId());
              if (change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP) {
                valueRepository.deleteById(change.getValueId());
              } else {
                valueRepository.deleteByFieldId(change.getFieldId());
                fieldRepository.deleteById(change.getFieldId());
              }
              return true;
            }));
  }

  private boolean lockCarriedValue(LibraryMetadataSchemaChange change, UUID documentId) {
    String sql =
        change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP
            ? "SELECT id FROM document_metadata_values WHERE document_id = ?"
                + " AND library_value_id = ? FOR UPDATE"
            : "SELECT id FROM document_metadata_values WHERE document_id = ?"
                + " AND library_field_id = ? FOR UPDATE";
    UUID subject =
        change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP
            ? change.getValueId()
            : change.getFieldId();
    return !jdbcTemplate.queryForList(sql, UUID.class, documentId, subject).isEmpty();
  }

  /** {@code false} when the retired value or field is already gone - the change is then stale. */
  private boolean lockSubject(LibraryMetadataSchemaChange change) {
    String sql =
        change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP
            ? "SELECT id FROM library_metadata_field_values WHERE id = ? FOR UPDATE"
            : "SELECT id FROM library_metadata_fields WHERE id = ? FOR UPDATE";
    UUID subject =
        change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP
            ? change.getValueId()
            : change.getFieldId();
    return !jdbcTemplate.queryForList(sql, UUID.class, subject).isEmpty();
  }

  /**
   * The next candidates in stable id order. A rewritten document leaves the selection, so the
   * offset only ever scans past what this Charge could not advance.
   */
  private List<UUID> selectDocuments(LibraryMetadataSchemaChange change, int limit, int offset) {
    String column =
        change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP
            ? "library_value_id"
            : "library_field_id";
    UUID subject =
        change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP
            ? change.getValueId()
            : change.getFieldId();
    return jdbcTemplate.query(
        "SELECT document_id FROM document_metadata_values WHERE "
            + column
            + " = ? ORDER BY document_id OFFSET ? LIMIT ?",
        (rs, index) -> (UUID) rs.getObject("document_id"),
        subject,
        offset,
        limit);
  }

  private long remainingDocuments(LibraryMetadataSchemaChange change) {
    return change.getKind() == LibraryMetadataSchemaChangeKind.VALUE_REMAP
        ? documentValueRepository.countByLibraryValueId(change.getValueId())
        : documentValueRepository.countByLibraryFieldId(change.getFieldId());
  }

  /** The running changes of {@code libraryId} with their remaining work, oldest first. */
  @Transactional(readOnly = true)
  public List<LibraryMetadataSchemaChangeView> pendingChanges(UUID libraryId) {
    List<LibraryMetadataSchemaChange> changes = changeRepository.findByLibraryId(libraryId);
    if (changes.isEmpty()) {
      return List.of();
    }
    Map<UUID, String> codesByValueId = new HashMap<>();
    Map<UUID, String> keysByFieldId = new HashMap<>();
    for (LibraryMetadataField field :
        fieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(libraryId)) {
      keysByFieldId.put(field.getId(), field.getFieldKey());
      for (LibraryMetadataFieldValue value :
          valueRepository.findByFieldIdOrderBySortOrderAscCodeAsc(field.getId())) {
        codesByValueId.put(value.getId(), value.getCode());
      }
    }
    List<LibraryMetadataSchemaChangeView> views = new ArrayList<>();
    for (LibraryMetadataSchemaChange change : changes) {
      views.add(
          new LibraryMetadataSchemaChangeView(
              change.getKind(),
              keysByFieldId.get(change.getFieldId()),
              codesByValueId.get(change.getValueId()),
              codesByValueId.get(change.getTargetValueId()),
              change.getProcessedDocuments(),
              remainingDocuments(change),
              change.getCorrelationRef()));
    }
    return List.copyOf(views);
  }

  /** The retired list values of {@code fieldIds} - the values no document may be given. */
  @Transactional(readOnly = true)
  public Set<UUID> retiredValueIds(Collection<UUID> fieldIds) {
    if (fieldIds.isEmpty()) {
      return Set.of();
    }
    Set<UUID> retired = new LinkedHashSet<>();
    for (LibraryMetadataSchemaChange change : changeRepository.findByFieldIdIn(fieldIds)) {
      if (change.getValueId() != null) {
        retired.add(change.getValueId());
      }
    }
    return retired;
  }

  /** The fields of {@code fieldIds} whose deletion is running - no value of them may be set. */
  @Transactional(readOnly = true)
  public Set<UUID> retiredFieldIds(Collection<UUID> fieldIds) {
    if (fieldIds.isEmpty()) {
      return Set.of();
    }
    Set<UUID> retired = new LinkedHashSet<>();
    for (LibraryMetadataSchemaChange change : changeRepository.findByFieldIdIn(fieldIds)) {
      if (change.getKind() == LibraryMetadataSchemaChangeKind.FIELD_DELETION) {
        retired.add(change.getFieldId());
      }
    }
    return retired;
  }

  /**
   * The schema-change state of every library in {@code libraryIds}, for the index status page. A
   * library without a pending change is absent from the result.
   */
  @Transactional(readOnly = true)
  public Map<UUID, LibraryMetadataSchemaChangeProgress> progressForLibraries(
      Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return Map.of();
    }
    List<Object> parameters = new ArrayList<>(libraryIds);
    Map<UUID, LibraryMetadataSchemaChangeProgress> byLibrary = new HashMap<>();
    jdbcTemplate.query(
        "SELECT f.library_id AS library_id, count(DISTINCT c.id) AS pending_changes,"
            + " count(DISTINCT d.document_id) AS pending_documents"
            + " FROM library_metadata_schema_changes c"
            + " JOIN library_metadata_fields f ON f.id = c.field_id"
            + " LEFT JOIN document_metadata_values d"
            + "   ON (c.value_id IS NOT NULL AND d.library_value_id = c.value_id)"
            + "   OR (c.value_id IS NULL AND d.library_field_id = c.field_id)"
            + " WHERE f.library_id IN ("
            + libraryIds.stream().map(id -> "?").collect(Collectors.joining(", "))
            + ") GROUP BY f.library_id",
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          byLibrary.put(
              libraryId,
              new LibraryMetadataSchemaChangeProgress(
                  libraryId,
                  rs.getLong("pending_changes"),
                  rs.getLong("pending_documents"),
                  lastSkippedByLibrary.getOrDefault(libraryId, 0L)));
        },
        parameters.toArray());
    return byLibrary;
  }
}
