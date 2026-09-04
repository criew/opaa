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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the core-field rows of a document (ADR-0024). {@link #applyDeterministicExtraction} runs
 * {@link CoreMetadataExtractor} and reconciles the result against the stored rows: a {@code MANUAL}
 * row is never touched, any other row is replaced by the fresh result or removed when the result is
 * empty, and the document's {@code metadata_extraction_version} is set to {@link
 * CoreMetadataExtractor#EXTRACTION_VERSION}. A system process within the ingest - no person's
 * rights context is consulted (Epic #1065, Beschluss 1).
 */
@Service
public class DocumentMetadataService {

  private static final Logger log = LoggerFactory.getLogger(DocumentMetadataService.class);

  private final DocumentMetadataValueRepository valueRepository;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final DocumentRepository documentRepository;
  private final DocumentPipelineRegistry pipelineRegistry;
  private final VectorChunkStore vectorChunkStore;

  public DocumentMetadataService(
      DocumentMetadataValueRepository valueRepository,
      DocumentTypeVocabularyRepository vocabularyRepository,
      DocumentRepository documentRepository,
      DocumentPipelineRegistry pipelineRegistry,
      VectorChunkStore vectorChunkStore) {
    this.valueRepository = valueRepository;
    this.vocabularyRepository = vocabularyRepository;
    this.documentRepository = documentRepository;
    this.pipelineRegistry = pipelineRegistry;
    this.vectorChunkStore = vectorChunkStore;
  }

  /**
   * Extracts the core fields of {@code documentId} from {@code fileName} and {@code properties} and
   * stores them, honouring existing {@code MANUAL} rows. Returns the effective values afterwards -
   * what {@code storeChunks} writes onto the document's chunks.
   */
  @Transactional
  public CoreMetadata applyDeterministicExtraction(
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
   * one otherwise - or deletes the non-manual row when {@code assign} is empty. A {@code MANUAL}
   * row is left untouched either way.
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
      if (current != null) {
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
   * Re-reads the core fields of an already indexed document from its original file - routing to the
   * same pipeline the ingest used, reading only its {@link
   * io.opaa.indexing.pipeline.DocumentPipeline#readProperties} - stores them and rewrites the
   * filterable keys on the document's existing chunks in place. No chunking, no embedding: the unit
   * of work the Bestandslauf (#1067) repeats per document.
   */
  public CoreMetadata reextractFromFile(Document document, Path file) {
    DocumentPipelineRegistry.Routed routed =
        pipelineRegistry.routedPipelineFor(file, document.getFileName());
    DocumentProperties properties =
        routed
            .pipeline()
            .readProperties(
                DocumentPipelineSource.ofFile(
                    file, document.getFileName(), routed.detectedExtension()));
    CoreMetadata core =
        applyDeterministicExtraction(document.getId(), document.getFileName(), properties);
    vectorChunkStore.updateDocumentMetadata(
        document.getId(), core.chunkMetadata(), CoreMetadataChunkKeys.ALL);
    return core;
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
      byDocument
          .computeIfAbsent(value.getDocumentId(), id -> new java.util.ArrayList<>())
          .add(value);
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
