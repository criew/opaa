package io.opaa.indexing.metadata;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual correction of a document's core fields (#1068, metadata-schema.md "Manuelle Korrektur ist
 * Teil des ersten Schnitts"): rights, validation and the audit event around {@link
 * DocumentMetadataService}'s row writes. Whoever may edit the library's documents ({@link
 * AssetRole#EDITOR}) may correct their metadata - no management right. Every change writes one
 * {@code DOCUMENT_METADATA_CHANGED} event per document and field with old and new value, so the
 * manual values of a library can be rebuilt from the audit log after a restore.
 */
@Service
public class DocumentMetadataCorrectionService {

  /** Upper bound of one Sammelzuweisung; the same limit the API schema declares. */
  static final int MAX_BULK_DOCUMENTS = 1000;

  static final String CORRELATION_PREFIX = "metadata-bulk-";

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final DocumentRepository documentRepository;
  private final DocumentMetadataService metadataService;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final UserRepository userRepository;
  private final AuditEventRecorder auditEventRecorder;

  public DocumentMetadataCorrectionService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      DocumentMetadataService metadataService,
      DocumentTypeVocabularyRepository vocabularyRepository,
      UserRepository userRepository,
      AuditEventRecorder auditEventRecorder) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.metadataService = metadataService;
    this.vocabularyRepository = vocabularyRepository;
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** Every core field of the document, empty ones included, for anyone who may read the library. */
  @Transactional(readOnly = true)
  public List<DocumentMetadataFieldView> fieldsOf(
      UUID libraryId, UUID documentId, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.VIEWER);
    Document document = requireDocument(library, documentId);
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    Map<String, MetadataValueSnapshot> byKey = new HashMap<>();
    for (MetadataValueSnapshot snapshot : metadataService.snapshotsFor(document.getId())) {
      byKey.put(snapshot.fieldKey(), snapshot);
    }
    Map<UUID, String> actorNames = actorNamesOf(byKey.values());
    List<DocumentMetadataFieldView> views = new ArrayList<>();
    for (CoreMetadataField field : CoreMetadataField.values()) {
      MetadataValueSnapshot value = byKey.get(field.key());
      views.add(
          new DocumentMetadataFieldView(
              field,
              value,
              value == null ? null : value.displayValue(vocabulary),
              value == null ? null : actorNames.get(value.actorUserId())));
    }
    return views;
  }

  /** Sets one field by hand; see the class Javadoc for rights and audit. */
  @Transactional
  public DocumentMetadataFieldView setValue(
      UUID libraryId,
      UUID documentId,
      String fieldKey,
      MetadataValueInput input,
      CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.EDITOR);
    Document document = requireDocument(library, documentId);
    CoreMetadataField field = requireField(fieldKey);
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    MetadataValueInput validated = input.validatedFor(field, vocabulary);

    ManualValueChange change =
        metadataService.setManualValue(document.getId(), field, validated, caller.id());
    if (change.changed()) {
      recordChange(library, document, field, change, caller, null, vocabulary);
    }
    MetadataValueSnapshot after = change.after();
    return new DocumentMetadataFieldView(
        field, after, after.displayValue(vocabulary), caller.displayName());
  }

  /** Removes one field's value; see the class Javadoc for rights and audit. */
  @Transactional
  public void deleteValue(UUID libraryId, UUID documentId, String fieldKey, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.EDITOR);
    Document document = requireDocument(library, documentId);
    CoreMetadataField field = requireField(fieldKey);

    ManualValueChange change = metadataService.deleteValue(document.getId(), field);
    if (change.changed()) {
      recordChange(library, document, field, change, caller, null, vocabularyRepository.snapshot());
    }
  }

  /**
   * One field, one value, the caller's own choice of this library's documents. An id that is not a
   * document of the library is rejected and reported, never silently skipped; the others are
   * processed in the same transaction, each with its own audit event sharing one correlationRef.
   */
  @Transactional
  public BulkMetadataResult bulkSetValue(
      UUID libraryId,
      String fieldKey,
      MetadataValueInput input,
      Collection<UUID> documentIds,
      CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.EDITOR);
    CoreMetadataField field = requireField(fieldKey);
    Set<UUID> requested = new LinkedHashSet<>(documentIds);
    if (requested.isEmpty()) {
      throw new ValidationException("Es wurde kein Dokument ausgewählt");
    }
    if (requested.size() > MAX_BULK_DOCUMENTS) {
      throw new ValidationException(
          "Höchstens " + MAX_BULK_DOCUMENTS + " Dokumente je Sammelzuweisung");
    }
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    MetadataValueInput validated = input.validatedFor(field, vocabulary);

    Map<UUID, Document> ownDocuments = new LinkedHashMap<>();
    for (Document document : documentRepository.findAllById(requested)) {
      if (library.getId().equals(document.getLibraryId())) {
        ownDocuments.put(document.getId(), document);
      }
    }
    List<UUID> rejected = new ArrayList<>();
    for (UUID id : requested) {
      if (!ownDocuments.containsKey(id)) {
        rejected.add(id);
      }
    }

    String correlationRef = CORRELATION_PREFIX + UUID.randomUUID();
    int updated = 0;
    int unchanged = 0;
    for (Document document : ownDocuments.values()) {
      ManualValueChange change =
          metadataService.setManualValue(document.getId(), field, validated, caller.id());
      if (change.changed()) {
        updated++;
        recordChange(library, document, field, change, caller, correlationRef, vocabulary);
      } else {
        unchanged++;
      }
    }
    return new BulkMetadataResult(updated, unchanged, List.copyOf(rejected), correlationRef);
  }

  private void recordChange(
      KnowledgeLibrary library,
      Document document,
      CoreMetadataField field,
      ManualValueChange change,
      CurrentUser caller,
      String correlationRef,
      DocumentTypeVocabulary vocabulary) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.DOCUMENT_METADATA_CHANGED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.getName())
            .before(payload(document, field, change.before(), vocabulary))
            .after(payload(document, field, change.after(), vocabulary))
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef(correlationRef)
            .build());
  }

  /**
   * The audit payload of one side of a change: document, field and either the full value
   * (machine-readable and display form, origin, provenance) or {@code state = EMPTY}. Never a
   * {@code null} map value - {@link AuditEvent} rejects those.
   */
  static Map<String, Object> payload(
      Document document,
      CoreMetadataField field,
      MetadataValueSnapshot snapshot,
      DocumentTypeVocabulary vocabulary) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("documentId", document.getId().toString());
    payload.put("fileName", document.getFileName());
    payload.put("fieldKey", field.key());
    if (snapshot == null) {
      payload.put("state", "EMPTY");
      return payload;
    }
    payload.put("state", snapshot.state().name());
    if (snapshot.value() != null) {
      payload.put("value", snapshot.value());
      payload.put("displayValue", snapshot.displayValue(vocabulary));
    }
    if (snapshot.datePrecision() != null) {
      payload.put("datePrecision", snapshot.datePrecision().name());
    }
    payload.put("origin", snapshot.origin().name());
    if (snapshot.extractionVersion() != null) {
      payload.put("extractionVersion", snapshot.extractionVersion());
    }
    if (snapshot.confidence() != null) {
      payload.put("confidence", snapshot.confidence());
    }
    if (snapshot.modelId() != null) {
      payload.put("modelId", snapshot.modelId());
    }
    return payload;
  }

  private Map<UUID, String> actorNamesOf(Collection<MetadataValueSnapshot> snapshots) {
    Set<UUID> actorIds = new LinkedHashSet<>();
    for (MetadataValueSnapshot snapshot : snapshots) {
      if (snapshot.actorUserId() != null) {
        actorIds.add(snapshot.actorUserId());
      }
    }
    Map<UUID, String> names = new HashMap<>();
    if (actorIds.isEmpty()) {
      return names;
    }
    for (User user : userRepository.findAllById(actorIds)) {
      names.put(user.getId(), user.getDisplayName());
    }
    return names;
  }

  /**
   * A library of another organization, or one the caller holds no right on at all, is absent (404);
   * too little right is 403 - {@link LibraryAccessService#requireRole}'s distinction.
   */
  private KnowledgeLibrary requireLibrary(UUID libraryId, CurrentUser caller, AssetRole required) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> candidate.getOrganizationId().equals(caller.organizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), required);
    return library;
  }

  /** A document of another library is as absent as one that does not exist. */
  private Document requireDocument(KnowledgeLibrary library, UUID documentId) {
    return documentRepository
        .findById(documentId)
        .filter(document -> library.getId().equals(document.getLibraryId()))
        .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));
  }

  private static CoreMetadataField requireField(String fieldKey) {
    return CoreMetadataField.fromKey(fieldKey)
        .orElseThrow(() -> new ValidationException("Unbekanntes Metadatenfeld: " + fieldKey));
  }
}
