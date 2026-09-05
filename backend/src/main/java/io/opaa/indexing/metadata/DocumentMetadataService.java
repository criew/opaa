package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the core-field rows of a document (ADR-0024). {@link #applyDeterministicExtraction} runs
 * {@link CoreMetadataExtractor} and reconciles the result against the stored rows: a {@code MANUAL}
 * row is never touched, a {@code DERIVED} row only yields to a real result, a {@code DETERMINISTIC}
 * row is replaced or removed, and the document's {@code metadata_extraction_version} is set to
 * {@link CoreMetadataExtractor#EXTRACTION_VERSION}. Writes run through a {@link
 * TransactionTemplate} rather than {@code @Transactional}, so {@link #reextractFromFile} can parse
 * outside and then clamp values and chunk propagation into one transaction without a
 * self-invocation. A system process within the ingest - no person's rights context is consulted
 * (Epic #1065, Beschluss 1).
 */
@Service
public class DocumentMetadataService {

  private static final Logger log = LoggerFactory.getLogger(DocumentMetadataService.class);

  private final DocumentMetadataValueRepository valueRepository;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final DocumentRepository documentRepository;
  private final DocumentPipelineRegistry pipelineRegistry;
  private final VectorChunkStore vectorChunkStore;
  private final LibraryMetadataFieldRepository libraryFieldRepository;
  private final TransactionTemplate transactionTemplate;

  public DocumentMetadataService(
      DocumentMetadataValueRepository valueRepository,
      DocumentTypeVocabularyRepository vocabularyRepository,
      DocumentRepository documentRepository,
      DocumentPipelineRegistry pipelineRegistry,
      VectorChunkStore vectorChunkStore,
      LibraryMetadataFieldRepository libraryFieldRepository,
      PlatformTransactionManager transactionManager) {
    this.valueRepository = valueRepository;
    this.vocabularyRepository = vocabularyRepository;
    this.documentRepository = documentRepository;
    this.pipelineRegistry = pipelineRegistry;
    this.vectorChunkStore = vectorChunkStore;
    this.libraryFieldRepository = libraryFieldRepository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Extracts the core fields of {@code documentId} from {@code fileName} and {@code properties} and
   * stores them in one transaction, honouring existing {@code MANUAL} and {@code DERIVED} rows.
   * Returns the effective values afterwards - what {@code storeChunks} writes onto the document's
   * chunks.
   */
  public DocumentChunkMetadata applyDeterministicExtraction(
      Document document, String fileName, DocumentProperties properties) {
    return transactionTemplate.execute(
        status -> {
          reconcileAll(document.getId(), fileName, properties);
          return chunkMetadataFor(document);
        });
  }

  /**
   * Every filterable value of {@code document} plus the complete key set its library manages - core
   * fields (ADR-0024) and the library's own filterable fields (#1071). Read whenever chunks are
   * written or rewritten, so a re-index never drops a library field a person set by hand.
   */
  @Transactional(readOnly = true)
  public DocumentChunkMetadata chunkMetadataFor(Document document) {
    return chunkMetadataOf(document, valueRepository.findByDocumentId(document.getId()));
  }

  /**
   * Rewrites the managed chunk keys of {@code document} from its stored values - the path a schema
   * change takes that moves no value but changes which keys the chunks must carry (#1071: a field
   * that starts or stops filtering).
   */
  public int rewriteChunkMetadata(Document document) {
    return transactionTemplate.execute(
        status -> {
          DocumentChunkMetadata chunkMetadata = chunkMetadataFor(document);
          return vectorChunkStore.updateDocumentMetadata(
              document.getId(), chunkMetadata.values(), chunkMetadata.managedKeys());
        });
  }

  private DocumentChunkMetadata chunkMetadataOf(
      Document document, List<DocumentMetadataValue> rows) {
    Map<String, Object> values =
        new LinkedHashMap<>(toCoreMetadata(rows, DocumentTypeVocabulary.empty()).chunkMetadata());
    Set<String> managed = new LinkedHashSet<>(CoreMetadataChunkKeys.ALL);
    if (document.getLibraryId() == null) {
      return new DocumentChunkMetadata(values, managed);
    }
    Map<String, DocumentMetadataValue> byKey = new HashMap<>();
    for (DocumentMetadataValue row : rows) {
      byKey.put(row.getFieldKey(), row);
    }
    for (LibraryMetadataField field :
        libraryFieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(
            document.getLibraryId())) {
      if (!field.isFilterEnabled()) {
        continue;
      }
      managed.add(field.chunkKey());
      managed.add(LibraryMetadataFieldKeys.precisionChunkKey(field.getFieldKey()));
      managed.add(LibraryMetadataFieldKeys.presenceChunkKey(field.getFieldKey()));
      DocumentMetadataValue row = byKey.get(field.documentFieldKey());
      if (row == null || row.getState() != MetadataValueState.SET) {
        continue;
      }
      if (row.getDateValue() != null) {
        values.put(field.chunkKey(), row.getDateValue().toString());
        values.put(
            LibraryMetadataFieldKeys.precisionChunkKey(field.getFieldKey()),
            row.getDatePrecision().name());
      } else if (row.getTextValue() != null) {
        values.put(field.chunkKey(), row.getTextValue());
      } else {
        continue;
      }
      values.put(
          LibraryMetadataFieldKeys.presenceChunkKey(field.getFieldKey()),
          LibraryMetadataFieldKeys.PRESENCE_VALUE);
    }
    return new DocumentChunkMetadata(values, managed);
  }

  /**
   * Re-reads the core fields of an already indexed document from its original file - routing to the
   * same pipeline the ingest used, reading only its {@link
   * io.opaa.indexing.pipeline.DocumentPipeline#readProperties} - then stores the values and
   * rewrites the filterable keys on the document's existing chunks. Parsing happens outside any
   * transaction (no pooled connection is held over PDFBox/POI); value rows and chunk propagation
   * are one transaction, so a failed chunk update leaves the document exactly as it was. No
   * chunking, no embedding: the unit of work the Bestandslauf (#1067) repeats per document.
   */
  public CoreMetadata reextractFromFile(Document document, Path file) {
    DocumentPipelineRegistry.Routed routed =
        pipelineRegistry.routedPipelineFor(file, document.getFileName());
    DocumentProperties properties =
        routed
            .pipeline()
            .readProperties(
                DocumentPipelineSource.ofFile(
                    file, document.getFileName(), routed.detectedExtension()))
            // Attached here for the same reason DocumentPipelineRunner attaches it on the ingest
            // path (#1263): the routed format is a source of the Dokumentart, not a pipeline's
            // finding.
            .withFormatExtension(routed.detectedExtension());
    return reextractFromProperties(document, properties);
  }

  /**
   * The transactional half of {@link #reextractFromFile} for a document whose declared properties
   * are already at hand without a file - an RSS entry's stored headline and publication date:
   * stores the values and rewrites the filterable keys on the existing chunks in one transaction.
   */
  public CoreMetadata reextractFromProperties(Document document, DocumentProperties properties) {
    return transactionTemplate.execute(
        status -> {
          CoreMetadata core = reconcileAll(document.getId(), document.getFileName(), properties);
          DocumentChunkMetadata chunkMetadata = chunkMetadataFor(document);
          vectorChunkStore.updateDocumentMetadata(
              document.getId(), chunkMetadata.values(), chunkMetadata.managedKeys());
          return core;
        });
  }

  /** The transactional body shared by both entry points; callers hold the transaction. */
  private CoreMetadata reconcileAll(
      UUID documentId, String fileName, DocumentProperties properties) {
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    ExtractedCoreMetadata extracted =
        CoreMetadataExtractor.extract(fileName, properties, vocabulary);
    Map<String, DocumentMetadataValue> existing = new HashMap<>();
    for (DocumentMetadataValue value : valueRepository.findByDocumentId(documentId)) {
      existing.put(value.getFieldKey(), value);
    }

    reconcile(
        documentId,
        CoreMetadataField.TITLE,
        existing,
        extracted.title().map(title -> value -> value.assignText(title)));
    reconcile(
        documentId,
        CoreMetadataField.DOCUMENT_TYPE,
        existing,
        extracted.documentTypeCode().map(code -> value -> value.assignVocabularyCode(code)));
    reconcile(
        documentId,
        CoreMetadataField.DOCUMENT_DATE,
        existing,
        extracted.date().map(date -> value -> value.assignDate(date.date(), date.precision())));

    int updated =
        documentRepository.updateMetadataExtractionVersion(
            documentId, CoreMetadataExtractor.EXTRACTION_VERSION);
    if (updated == 0) {
      log.debug(
          "Document {} vanished before its metadata extraction version could be recorded",
          documentId);
    }
    return toCoreMetadata(valueRepository.findByDocumentId(documentId), vocabulary);
  }

  /**
   * Applies {@code assign} to {@code field}'s row - reusing an existing non-manual row, creating
   * one otherwise. An empty {@code assign} deletes only a {@code DETERMINISTIC} row: a {@code
   * DERIVED} value fills exactly the gap the deterministic step leaves and survives it; a {@code
   * MANUAL} row is left untouched either way.
   */
  private void reconcile(
      UUID documentId,
      CoreMetadataField field,
      Map<String, DocumentMetadataValue> existing,
      Optional<Consumer<DocumentMetadataValue>> assign) {
    DocumentMetadataValue current = existing.get(field.key());
    if (current != null && current.getOrigin() == MetadataOrigin.MANUAL) {
      return;
    }
    if (assign.isEmpty()) {
      if (current != null && current.getOrigin() == MetadataOrigin.DETERMINISTIC) {
        valueRepository.delete(current);
      }
      return;
    }
    DocumentMetadataValue target;
    if (current != null) {
      current.markDeterministic(CoreMetadataExtractor.EXTRACTION_VERSION);
      target = current;
    } else {
      target =
          DocumentMetadataValue.deterministic(
              documentId, field, CoreMetadataExtractor.EXTRACTION_VERSION);
    }
    assign.get().accept(target);
    valueRepository.save(target);
  }

  /**
   * Sets {@code field} of {@code documentId} to {@code input} (already validated) by hand: the row
   * becomes {@code MANUAL} with {@code actorUserId}, whatever its origin was, and the filterable
   * chunk keys are rewritten in the same transaction. A row that already holds exactly this manual
   * value is left alone and reported as unchanged. Rights and audit are the caller's ({@link
   * DocumentMetadataCorrectionService}); this method only owns the rows.
   */
  public ManualValueChange setManualValue(
      UUID documentId, MetadataFieldRef field, MetadataValueInput input, UUID actorUserId) {
    return transactionTemplate.execute(
        status -> {
          DocumentMetadataValue current =
              valueRepository.findByDocumentIdAndFieldKey(documentId, field.key()).orElse(null);
          MetadataValueSnapshot before = current == null ? null : MetadataValueSnapshot.of(current);
          if (before != null && before.origin() == MetadataOrigin.MANUAL && before.holds(input)) {
            return ManualValueChange.unchanged(before);
          }
          DocumentMetadataValue target;
          if (current != null) {
            current.markManual(actorUserId);
            target = current;
          } else {
            target =
                DocumentMetadataValue.manual(
                    documentId, field.key(), field.libraryFieldId(), actorUserId);
          }
          input.applyTo(target);
          valueRepository.save(target);
          propagateToChunks(documentId, field);
          return new ManualValueChange(before, MetadataValueSnapshot.of(target), true);
        });
  }

  /**
   * Removes the row of {@code field}, whatever its origin, so the field is empty again and the next
   * extraction may fill it: the document's extraction version is cleared in the same transaction,
   * which is what puts it back into the Bestandslauf's selection (a file that never changes would
   * otherwise never be read again). Chunk keys are rewritten in the same transaction; an already
   * empty field is reported as unchanged.
   */
  public ManualValueChange deleteValue(UUID documentId, MetadataFieldRef field) {
    return transactionTemplate.execute(
        status -> {
          DocumentMetadataValue current =
              valueRepository.findByDocumentIdAndFieldKey(documentId, field.key()).orElse(null);
          if (current == null) {
            return ManualValueChange.unchanged(null);
          }
          MetadataValueSnapshot before = MetadataValueSnapshot.of(current);
          valueRepository.delete(current);
          if (!field.isLibraryField()) {
            // Only a core field is filled by an extraction at all; clearing the version for a
            // library field would hand the document to a Bestandslauf that cannot fill it.
            documentRepository.clearMetadataExtractionVersion(documentId);
          }
          propagateToChunks(documentId, field);
          return new ManualValueChange(before, null, true);
        });
  }

  /**
   * The title is not a chunk key (ADR-0024, Entscheidung 5) and is skipped; every other field -
   * core or library - rewrites the document's managed keys. The chunk keys carry codes, never
   * labels, so no vocabulary is loaded here.
   */
  private void propagateToChunks(UUID documentId, MetadataFieldRef field) {
    if (!field.affectsChunkKeys()) {
      return;
    }
    Document document = documentRepository.findById(documentId).orElse(null);
    if (document == null) {
      return;
    }
    valueRepository.flush();
    DocumentChunkMetadata chunkMetadata =
        chunkMetadataOf(document, valueRepository.findByDocumentId(documentId));
    vectorChunkStore.updateDocumentMetadata(
        documentId, chunkMetadata.values(), chunkMetadata.managedKeys());
  }

  /** Every stored row of {@code documentId} as detached snapshots, in no particular order. */
  @Transactional(readOnly = true)
  public List<MetadataValueSnapshot> snapshotsFor(UUID documentId) {
    return valueRepository.findByDocumentId(documentId).stream()
        .map(MetadataValueSnapshot::of)
        .toList();
  }

  @Transactional(readOnly = true)
  public CoreMetadata coreMetadataFor(UUID documentId) {
    return toCoreMetadata(
        valueRepository.findByDocumentId(documentId), vocabularyRepository.snapshot());
  }

  /**
   * The effective core fields of every document in {@code documentIds}; absent for a document
   * without any.
   */
  @Transactional(readOnly = true)
  public Map<UUID, CoreMetadata> coreMetadataFor(Collection<UUID> documentIds) {
    if (documentIds.isEmpty()) {
      return Map.of();
    }
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    Map<UUID, List<DocumentMetadataValue>> byDocument = new LinkedHashMap<>();
    for (DocumentMetadataValue value : valueRepository.findByDocumentIdIn(documentIds)) {
      byDocument.computeIfAbsent(value.getDocumentId(), id -> new ArrayList<>()).add(value);
    }
    Map<UUID, CoreMetadata> result = new LinkedHashMap<>();
    byDocument.forEach((id, values) -> result.put(id, toCoreMetadata(values, vocabulary)));
    return result;
  }

  private static CoreMetadata toCoreMetadata(
      List<DocumentMetadataValue> values, DocumentTypeVocabulary vocabulary) {
    String title = null;
    MetadataOrigin titleOrigin = null;
    String typeCode = null;
    String typeLabel = null;
    MetadataOrigin typeOrigin = null;
    LocalDate date = null;
    DatePrecision precision = null;
    MetadataOrigin dateOrigin = null;
    for (DocumentMetadataValue value : values) {
      if (value.getState() != MetadataValueState.SET) {
        continue;
      }
      if (CoreMetadataField.TITLE.key().equals(value.getFieldKey())) {
        title = value.getTextValue();
        titleOrigin = value.getOrigin();
      } else if (CoreMetadataField.DOCUMENT_TYPE.key().equals(value.getFieldKey())) {
        typeCode = value.getVocabularyCode();
        typeLabel = vocabulary.labelOf(typeCode).orElse(typeCode);
        typeOrigin = value.getOrigin();
      } else if (CoreMetadataField.DOCUMENT_DATE.key().equals(value.getFieldKey())) {
        date = value.getDateValue();
        precision = value.getDatePrecision();
        dateOrigin = value.getOrigin();
      }
    }
    return new CoreMetadata(
        title, titleOrigin, typeCode, typeLabel, typeOrigin, date, precision, dateOrigin);
  }
}
