package io.opaa.indexing.metadata;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.ContextPrefixRerunService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentBatchLoop;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.EmbeddingRateEstimator;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The schema configuration of a library's own metadata fields (metadata-schema.md Teil II (b) and
 * Teil V "Der erste Schnitt"). Two rules of the specification live here and are what this service
 * exists for:
 *
 * <ul>
 *   <li><b>Aufnahmeregel</b> - a field is only accepted when it serves the filter or the
 *       Kontextpräfix; "nur Beleg-Anzeige" is rejected, and at most {@link #MAX_FIELDS} fields
 *       exist per library.
 *   <li><b>Abbildungsregel</b> - a value of a controlled list can only be removed or replaced
 *       together with a confirmed mapping onto another value or onto "leer", and the number of
 *       affected documents is known before the confirmation. The database enforces the same rule
 *       from below through {@code ON DELETE RESTRICT}.
 * </ul>
 *
 * <p><b>Rights.</b> Changing the schema needs the management right at the library ({@link
 * AssetRole#MANAGER}) - the same bar as changing the library's other settings - while setting a
 * value stays at the editing right of . Reading the schema, including the configured value lists,
 * needs only {@link AssetRole#VIEWER}: the list is schema, not an aggregate over documents
 * (metadata-schema.md, Rechte-Invariante), which is also why a value list must never carry
 * schutzbedürftige Bezeichnungen.
 *
 * <p><b>Kontextpräfix.</b> A change to a prefix-effective field raises the library's context-prefix
 * version and thereby hands its whole indexed bestand to the Nachlauf ({@code
 * ContextPrefixRerunService}); saving moves nothing itself. What that will cost is answered
 * beforehand by {@link #changeImpact}.
 */
@Service
public class LibraryMetadataFieldService {

  /**
   * "Bis zu etwa fünf" of the specification, as a hard bound: every field wants filling on every
   * future document, and the bound is what keeps a schema from growing into fifteen fields nobody
   * maintains.
   */
  public static final int MAX_FIELDS = 5;

  private static final int MAX_VALUES = 100;
  private static final int REMAP_BATCH_SIZE = 500;
  private static final String CORRELATION_PREFIX = "metadata-remap-";
  private static final Pattern FIELD_KEY = Pattern.compile("^[a-z][a-z0-9_]*$");
  private static final Pattern VALUE_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]*$");

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final LibraryMetadataFieldRepository fieldRepository;
  private final LibraryMetadataFieldValueRepository valueRepository;
  private final DocumentMetadataValueRepository documentValueRepository;
  private final DocumentRepository documentRepository;
  private final DocumentMetadataService metadataService;
  private final DocumentMetadataCorrectionService correctionService;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final EmbeddingRateEstimator embeddingRateEstimator;
  private final ContextPrefixRerunService contextPrefixRerunService;
  private final ApplicationEventPublisher eventPublisher;

  public LibraryMetadataFieldService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      LibraryMetadataFieldRepository fieldRepository,
      LibraryMetadataFieldValueRepository valueRepository,
      DocumentMetadataValueRepository documentValueRepository,
      DocumentRepository documentRepository,
      DocumentMetadataService metadataService,
      DocumentMetadataCorrectionService correctionService,
      DocumentTypeVocabularyRepository vocabularyRepository,
      EmbeddingRateEstimator embeddingRateEstimator,
      ContextPrefixRerunService contextPrefixRerunService,
      ApplicationEventPublisher eventPublisher) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.fieldRepository = fieldRepository;
    this.valueRepository = valueRepository;
    this.documentValueRepository = documentValueRepository;
    this.documentRepository = documentRepository;
    this.metadataService = metadataService;
    this.correctionService = correctionService;
    this.vocabularyRepository = vocabularyRepository;
    this.embeddingRateEstimator = embeddingRateEstimator;
    this.contextPrefixRerunService = contextPrefixRerunService;
    this.eventPublisher = eventPublisher;
  }

  /** The library's fields with their configured value lists; readable with {@code VIEWER}. */
  @Transactional(readOnly = true)
  public List<LibraryMetadataFieldDefinition> fieldsOf(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.VIEWER);
    return definitionsOf(library.getId());
  }

  /**
   * The whole "Metadatenfelder" section of a library's settings in one read: fields, the
   * Kontextpraefix-Wirkstellen of the core fields and the documents waiting for the Nachlauf. Same
   * {@code VIEWER} bar as {@link #fieldsOf}.
   */
  @Transactional(readOnly = true)
  public LibraryMetadataFieldOverview overviewOf(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.VIEWER);
    return new LibraryMetadataFieldOverview(
        definitionsOf(library.getId()),
        CoreContextPrefixSettings.of(library),
        contextPrefixRerunService.pendingDocuments(library.getId()));
  }

  /** The fields of several libraries at once - the filter interface's own read. */
  @Transactional(readOnly = true)
  public Map<UUID, List<LibraryMetadataFieldDefinition>> fieldsOfLibraries(Set<UUID> libraryIds) {
    Map<UUID, List<LibraryMetadataFieldDefinition>> byLibrary = new LinkedHashMap<>();
    if (libraryIds.isEmpty()) {
      return byLibrary;
    }
    List<LibraryMetadataField> fields =
        fieldRepository.findByLibraryIdInOrderBySortOrderAscFieldKeyAsc(libraryIds);
    Map<UUID, List<LibraryMetadataFieldValue>> valuesByField = valuesOf(fields);
    for (LibraryMetadataField field : fields) {
      byLibrary
          .computeIfAbsent(field.getLibraryId(), id -> new ArrayList<>())
          .add(
              new LibraryMetadataFieldDefinition(
                  field, valuesByField.getOrDefault(field.getId(), List.of())));
    }
    return byLibrary;
  }

  /**
   * Defines a new field. Rejects a field without a retrieval effect (400), a sixth field (409), a
   * duplicate key (409) and a citation position already taken (409).
   */
  @Transactional
  public LibraryMetadataFieldDefinition createField(
      UUID libraryId, LibraryMetadataFieldInput input, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    String key = requireFieldKey(input.fieldKey());
    requireRetrievalEffect(input.filter(), input.contextPrefix());
    String label = requireLabel(input.label(), "Der Feldname");
    if (input.type() == null) {
      throw new ValidationException("Für das Feld ist ein Typ erforderlich");
    }
    String pattern = validatedPattern(input.type(), input.valuePattern());
    if (fieldRepository.countByLibraryId(library.getId()) >= MAX_FIELDS) {
      throw new ConflictException(
          "Eine Bibliothek führt höchstens " + MAX_FIELDS + " eigene Metadatenfelder");
    }
    if (fieldRepository.findByLibraryIdAndFieldKey(library.getId(), key).isPresent()) {
      throw new ConflictException("Das Feld „" + key + "“ gibt es in dieser Bibliothek bereits");
    }
    Integer citationPosition =
        validatedCitationPosition(library.getId(), null, input.citationPosition());

    int sortOrder = (int) fieldRepository.countByLibraryId(library.getId()) * 10 + 10;
    LibraryMetadataField field =
        new LibraryMetadataField(library.getId(), key, input.type(), pattern, sortOrder);
    field.apply(label, input.filter(), input.contextPrefix(), citationPosition);
    fieldRepository.save(field);

    List<LibraryMetadataFieldValue> values = new ArrayList<>();
    if (input.type() == LibraryMetadataFieldType.SELECT) {
      if (input.values().isEmpty()) {
        throw new ValidationException("Ein Auswahlfeld braucht mindestens einen Wert");
      }
      Set<String> seen = new LinkedHashSet<>();
      int order = 0;
      for (LibraryMetadataFieldInput.LibraryFieldValueInput value : input.values()) {
        String code = requireValueCode(value.code());
        if (!seen.add(code)) {
          throw new ValidationException("Der Wert „" + code + "“ steht doppelt in der Werteliste");
        }
        order += 10;
        values.add(
            valueRepository.save(
                new LibraryMetadataFieldValue(
                    field.getId(),
                    code,
                    requireLabel(value.label(), "Die Wertebezeichnung"),
                    order)));
      }
      requireValueCount(values.size());
    } else if (!input.values().isEmpty()) {
      throw new ValidationException("Nur ein Auswahlfeld führt eine Werteliste");
    }
    // No marking here on purpose: a field that does not exist yet carries no value on any
    // document, so no document's prefix changes - which is exactly what FIELD_ADDED reports.
    schemaChanged(library);
    return new LibraryMetadataFieldDefinition(field, values);
  }

  /**
   * Changes label and Wirkstellen of an existing field. Type, key and value list are not changed
   * here: the type is what every stored value was checked against, and the list has its own
   * operations with their mapping rule.
   */
  @Transactional
  public LibraryMetadataFieldDefinition updateField(
      UUID libraryId,
      String fieldKey,
      String label,
      boolean filter,
      boolean contextPrefix,
      Integer citationPosition,
      CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    LibraryMetadataField field = requireField(library, fieldKey);
    requireRetrievalEffect(filter, contextPrefix);
    boolean wasFilterable = field.isFilterEnabled();
    boolean wasPrefixEffective = field.isContextPrefixEnabled();
    field.apply(
        requireLabel(label, "Der Feldname"),
        filter,
        contextPrefix,
        validatedCitationPosition(library.getId(), field.getId(), citationPosition));
    fieldRepository.save(field);
    if (wasFilterable != filter) {
      // The chunk keys are what both search paths read; a field that stopped filtering must lose
      // them, one that started filtering must gain them on the documents that carry a value.
      rewriteChunksOf(field);
    }
    if (wasPrefixEffective != contextPrefix) {
      // Both directions cost the same, and both cost it for exactly the documents that carry a
      // value: for every other document the prefix is unchanged.
      documentRepository.clearContextPrefixStampForField(library.getId(), field.documentFieldKey());
    }
    schemaChanged(library);
    return new LibraryMetadataFieldDefinition(field, valuesOfField(field.getId()));
  }

  /** How many documents carry a value of this field - shown before a deletion is confirmed. */
  @Transactional(readOnly = true)
  public long fieldUsage(UUID libraryId, String fieldKey, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    return documentValueRepository.countByLibraryFieldId(requireField(library, fieldKey).getId());
  }

  /**
   * Removes a field with everything that hangs on it: its stored document values, its chunk keys
   * and its value list. The values are removed explicitly before the field, so the {@code ON DELETE
   * RESTRICT} of the value list never has to decide the order.
   */
  @Transactional
  public void deleteField(UUID libraryId, String fieldKey, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    LibraryMetadataField field = requireField(library, fieldKey);
    MetadataFieldRef ref = MetadataFieldRef.of(field);
    // While the field still exists, its chunk keys are part of what a rewrite owns - so deleting
    // the value of every document that carries one strips those keys along the way. Paged like the
    // value mapping rather than loaded at once.
    forEachDocumentWithAValue(
        field, false, documentId -> metadataService.deleteValue(documentId, ref));
    valueRepository.deleteByFieldId(field.getId());
    fieldRepository.delete(field);
    // The documents that carried a value were already handed to the Nachlauf by deleteValue above,
    // which marks whatever it empties on a prefix-effective field - no second marking here.
    schemaChanged(library);
  }

  /**
   * Every document carrying a value of {@code field}, in pages of {@link #REMAP_BATCH_SIZE} rather
   * than in one list - the same memory bound the value mapping keeps.
   *
   * @param rowsSurvive whether {@code action} leaves the value row in place. It does for a chunk
   *     rewrite, which then has to page forward; a deletion instead shrinks the selection, so the
   *     next page is again the first one.
   */
  private void forEachDocumentWithAValue(
      LibraryMetadataField field, boolean rowsSurvive, java.util.function.Consumer<UUID> action) {
    int page = 0;
    while (true) {
      List<UUID> documentIds =
          documentValueRepository.findDocumentIdsByLibraryFieldId(
              field.getId(), PageRequest.of(page, REMAP_BATCH_SIZE));
      if (documentIds.isEmpty()) {
        return;
      }
      documentIds.forEach(action);
      if (documentIds.size() < REMAP_BATCH_SIZE) {
        return;
      }
      if (rowsSurvive) {
        page++;
      }
    }
  }

  /** Adds one entry to a SELECT field's value list - no effect on any stored value, no Nachlauf. */
  @Transactional
  public LibraryMetadataFieldDefinition addValue(
      UUID libraryId, String fieldKey, String code, String label, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    LibraryMetadataField field = requireSelectField(library, fieldKey);
    String validCode = requireValueCode(code);
    if (valueRepository.findByFieldIdAndCode(field.getId(), validCode).isPresent()) {
      throw new ConflictException("Der Wert „" + validCode + "“ steht bereits in der Werteliste");
    }
    List<LibraryMetadataFieldValue> existing = valuesOfField(field.getId());
    requireValueCount(existing.size() + 1);
    int sortOrder =
        existing.stream().mapToInt(LibraryMetadataFieldValue::getSortOrder).max().orElse(0) + 10;
    valueRepository.save(
        new LibraryMetadataFieldValue(
            field.getId(), validCode, requireLabel(label, "Die Wertebezeichnung"), sortOrder));
    schemaChanged(library);
    return new LibraryMetadataFieldDefinition(field, valuesOfField(field.getId()));
  }

  /**
   * Corrects the German label of one list entry. The code stays - it is what documents carry, so
   * relabeling has no Rückwirkung and needs no mapping; replacing a code is a removal with one.
   */
  @Transactional
  public LibraryMetadataFieldDefinition relabelValue(
      UUID libraryId, String fieldKey, String code, String label, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    LibraryMetadataField field = requireSelectField(library, fieldKey);
    LibraryMetadataFieldValue value = requireValue(field, code);
    value.relabel(requireLabel(label, "Die Wertebezeichnung"));
    valueRepository.save(value);
    if (field.isContextPrefixEnabled()) {
      // A document carries the code, so no value moves - but the Kontextpraefix carries the label,
      // so the indexed text of the documents carrying exactly this value does change.
      documentRepository.clearContextPrefixStampForValue(library.getId(), value.getId());
    }
    schemaChanged(library);
    return new LibraryMetadataFieldDefinition(field, valuesOfField(field.getId()));
  }

  /**
   * How many documents carry {@code code} - the Folgekosten that stand <b>before</b> the
   * confirmation of a mapping (metadata-schema.md: "Die Zahl der betroffenen Dokumente steht vor
   * der Bestätigung fest").
   */
  @Transactional(readOnly = true)
  public long valueUsage(UUID libraryId, String fieldKey, String code, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.VIEWER);
    LibraryMetadataField field = requireSelectField(library, fieldKey);
    return documentValueRepository.countByLibraryValueId(requireValue(field, code).getId());
  }

  /**
   * Removes {@code code} from the list, mapping every document that carries it onto {@code
   * targetCode} or - when that is {@code null} - onto "leer". One transaction: the list entry is
   * gone exactly when every document has been rewritten, so "Dokument trägt einen Wert, den es im
   * Schema nicht mehr gibt" never exists, not even briefly. Every document gets its own audit event
   * with its old value, all sharing one correlationRef, exactly like a Sammelzuweisung.
   */
  @Transactional
  public LibraryFieldValueRemapResult remapValue(
      UUID libraryId, String fieldKey, String code, String targetCode, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    LibraryMetadataField field = requireSelectField(library, fieldKey);
    LibraryMetadataFieldValue removed = requireValue(field, code);
    LibraryMetadataFieldValue target = null;
    if (targetCode != null) {
      if (targetCode.equals(code)) {
        throw new ValidationException("Ein Wert kann nicht auf sich selbst abgebildet werden");
      }
      target = requireValue(field, targetCode);
    }
    MetadataFieldRef ref = MetadataFieldRef.of(field);
    MetadataValueInput replacement =
        target == null ? null : MetadataValueInput.libraryValue(target.getCode(), target.getId());
    String correlationRef = CORRELATION_PREFIX + UUID.randomUUID();
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    UUID removedId = removed.getId();

    // The same chargen loop the Bestandslauf and the pipeline re-index run on, driven until
    // no document carries the value any more: every advanced document loses the reference and
    // leaves the selection, so the offset stays 0 and the outer loop terminates by construction.
    // The list entry is deleted only afterwards and in the same transaction - that ordering is what
    // makes "Dokument trägt einen Wert, den es im Schema nicht mehr gibt" unreachable.
    long remapped = 0;
    long cleared = 0;
    for (long remaining = documentValueRepository.countByLibraryValueId(removedId);
        remaining > 0;
        remaining = documentValueRepository.countByLibraryValueId(removedId)) {
      Map<Advance, Integer> counts =
          DocumentBatchLoop.run(
              (int) Math.min(remaining, REMAP_BATCH_SIZE),
              Advance.class,
              Advance.SKIPPED,
              (limit, offset) ->
                  documentValueRepository.findDocumentIdsByLibraryValueId(
                      removedId, PageRequest.of(0, limit)),
              documentId ->
                  remapDocument(
                      library, documentId, ref, replacement, caller, correlationRef, vocabulary));
      remapped += counts.get(Advance.REMAPPED);
      cleared += counts.get(Advance.CLEARED);
      if (counts.get(Advance.REMAPPED) + counts.get(Advance.CLEARED) == 0) {
        throw new IllegalStateException(
            "Value remapping made no progress for library metadata field value " + removedId);
      }
    }

    valueRepository.delete(removed);
    schemaChanged(library);
    return new LibraryFieldValueRemapResult(remapped, cleared, correlationRef);
  }

  /**
   * The Folgekosten of a planned change to {@code fieldKey} (metadata-schema.md, "Der
   * Reindex-Preis, ehrlich ausgewiesen"): affected documents and chunks, the embedding calls that
   * follows and the expected runtime. Read-only - asking costs nothing and changes nothing. {@code
   * fieldKey} names either a core field or one of the library's own fields; the management right is
   * the same bar the change itself needs.
   */
  @Transactional(readOnly = true)
  public MetadataChangeImpact changeImpact(
      UUID libraryId, String fieldKey, MetadataChangeKind kind, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    if (kind == MetadataChangeKind.VALUE_ADDED || kind == MetadataChangeKind.FIELD_ADDED) {
      // Extending a list has no rueckwirkung on a stored value, and a field that does not exist yet
      // carries none - the two rows of the Kostentabelle that are free whatever the Wirkstellen
      // are.
      // A new field starts costing only once values reach it, one document at a time.
      return MetadataChangeImpact.free(embeddingRateEstimator.rateSource());
    }
    CoreMetadataField coreField = CoreMetadataField.fromKey(fieldKey).orElse(null);
    boolean prefixEffective;
    DocumentMetadataValueRepository.FieldImpactCount count;
    if (coreField != null) {
      if (kind == MetadataChangeKind.FIELD_REMOVED || kind == MetadataChangeKind.VALUE_REMOVED) {
        throw new ValidationException("Ein Kernfeld kann nicht entfernt werden");
      }
      prefixEffective = true;
      count =
          documentValueRepository.impactOfField(
              library.getId(), DocumentStatus.INDEXED, coreField.key());
    } else {
      LibraryMetadataField field = requireField(library, fieldKey);
      prefixEffective =
          kind == MetadataChangeKind.CONTEXT_PREFIX_ENABLED
              || kind == MetadataChangeKind.CONTEXT_PREFIX_DISABLED
              || field.isContextPrefixEnabled();
      // A value mapping named without its value is answered with the whole field's usage - the
      // honest upper bound; the exact figure comes from valueChangeImpact, which knows the value.
      count =
          documentValueRepository.impactOfField(
              library.getId(), DocumentStatus.INDEXED, field.documentFieldKey());
    }
    return impactOf(count, prefixEffective);
  }

  /**
   * The Folgekosten of removing exactly {@code code} from a SELECT field's list - the number behind
   * the Abbildungsdialog, and the re-embedding it costs when the field is prefix-effective.
   */
  @Transactional(readOnly = true)
  public MetadataChangeImpact valueChangeImpact(
      UUID libraryId, String fieldKey, String code, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    LibraryMetadataField field = requireSelectField(library, fieldKey);
    LibraryMetadataFieldValue value = requireValue(field, code);
    return impactOf(
        documentValueRepository.impactOfValue(
            library.getId(), DocumentStatus.INDEXED, value.getId()),
        field.isContextPrefixEnabled());
  }

  private MetadataChangeImpact impactOf(
      DocumentMetadataValueRepository.FieldImpactCount count, boolean prefixEffective) {
    long documents = count == null ? 0 : count.getDocumentCount();
    long chunks =
        !prefixEffective || count == null || count.getChunkCount() == null
            ? 0
            : count.getChunkCount();
    return new MetadataChangeImpact(
        documents,
        chunks,
        chunks,
        embeddingRateEstimator.estimatedSeconds(chunks),
        prefixEffective && documents > 0,
        embeddingRateEstimator.rateSource());
  }

  /** The Kontextpraefix-Wirkstellen of the core fields, as configured for this library. */
  @Transactional(readOnly = true)
  public CoreContextPrefixSettings coreContextPrefix(UUID libraryId, CurrentUser caller) {
    return CoreContextPrefixSettings.of(requireLibrary(libraryId, caller, AssetRole.VIEWER));
  }

  /**
   * Switches the Kontextpraefix-Wirkstelle of Dokumentart and Datum/Stand and hands exactly the
   * documents carrying a value for a switched field to the Nachlauf; it starts nothing - that is a
   * separate, explicit release on the administration page.
   */
  @Transactional
  public CoreContextPrefixSettings updateCoreContextPrefix(
      UUID libraryId, boolean documentType, boolean documentDate, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    boolean typeSwitched = library.isCoreContextPrefixDocumentType() != documentType;
    boolean dateSwitched = library.isCoreContextPrefixDocumentDate() != documentDate;
    if (library.applyCoreContextPrefix(documentType, documentDate)) {
      libraryRepository.save(library);
      if (typeSwitched) {
        documentRepository.clearContextPrefixStampForField(
            library.getId(), CoreMetadataField.DOCUMENT_TYPE.key());
      }
      if (dateSwitched) {
        documentRepository.clearContextPrefixStampForField(
            library.getId(), CoreMetadataField.DOCUMENT_DATE.key());
      }
      schemaChanged(library);
    }
    return CoreContextPrefixSettings.of(library);
  }

  /**
   * Announces the schema change so every derived view is rebuilt - today the per-person filter
   * options cache, whose entries would otherwise offer a removed value (or hide a new field) for up
   * to its TTL. Published on the transaction, delivered after it completes.
   */
  private void schemaChanged(KnowledgeLibrary library) {
    eventPublisher.publishEvent(new LibraryMetadataSchemaChanged(library.getId()));
  }

  /**
   * The outcomes of one remapped document. {@code SKIPPED} exists only because the shared loop
   * needs a name for "did not advance"; inside one transaction every value row still has its
   * document (foreign key), so it is never returned.
   */
  private enum Advance {
    REMAPPED,
    CLEARED,
    SKIPPED
  }

  private Advance remapDocument(
      KnowledgeLibrary library,
      UUID documentId,
      MetadataFieldRef ref,
      MetadataValueInput replacement,
      CurrentUser caller,
      String correlationRef,
      DocumentTypeVocabulary vocabulary) {
    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));
    ManualValueChange change =
        replacement == null
            ? metadataService.deleteValue(documentId, ref)
            : metadataService.setManualValue(documentId, ref, replacement, caller.id());
    if (change.changed()) {
      correctionService.recordChange(
          library, document, ref, change, caller, correlationRef, vocabulary);
    }
    return replacement == null ? Advance.CLEARED : Advance.REMAPPED;
  }

  private void rewriteChunksOf(LibraryMetadataField field) {
    forEachDocumentWithAValue(
        field,
        true,
        documentId ->
            documentRepository
                .findById(documentId)
                .ifPresent(metadataService::rewriteChunkMetadata));
  }

  private List<LibraryMetadataFieldDefinition> definitionsOf(UUID libraryId) {
    List<LibraryMetadataField> fields =
        fieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(libraryId);
    Map<UUID, List<LibraryMetadataFieldValue>> valuesByField = valuesOf(fields);
    List<LibraryMetadataFieldDefinition> definitions = new ArrayList<>();
    for (LibraryMetadataField field : fields) {
      definitions.add(
          new LibraryMetadataFieldDefinition(
              field, valuesByField.getOrDefault(field.getId(), List.of())));
    }
    return definitions;
  }

  private Map<UUID, List<LibraryMetadataFieldValue>> valuesOf(List<LibraryMetadataField> fields) {
    Map<UUID, List<LibraryMetadataFieldValue>> byField = new LinkedHashMap<>();
    if (fields.isEmpty()) {
      return byField;
    }
    List<UUID> ids = fields.stream().map(LibraryMetadataField::getId).toList();
    for (LibraryMetadataFieldValue value :
        valueRepository.findByFieldIdInOrderBySortOrderAscCodeAsc(ids)) {
      byField.computeIfAbsent(value.getFieldId(), id -> new ArrayList<>()).add(value);
    }
    return byField;
  }

  private List<LibraryMetadataFieldValue> valuesOfField(UUID fieldId) {
    return valueRepository.findByFieldIdOrderBySortOrderAscCodeAsc(fieldId);
  }

  private KnowledgeLibrary requireLibrary(UUID libraryId, CurrentUser caller, AssetRole required) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> candidate.getOrganizationId().equals(caller.organizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), required);
    return library;
  }

  private LibraryMetadataField requireField(KnowledgeLibrary library, String fieldKey) {
    String key = LibraryMetadataFieldKeys.fieldKeyOf(fieldKey).orElse(fieldKey);
    return fieldRepository
        .findByLibraryIdAndFieldKey(library.getId(), key)
        .orElseThrow(() -> new NotFoundException("Metadatenfeld nicht gefunden"));
  }

  private LibraryMetadataField requireSelectField(KnowledgeLibrary library, String fieldKey) {
    LibraryMetadataField field = requireField(library, fieldKey);
    if (field.getType() != LibraryMetadataFieldType.SELECT) {
      throw new ValidationException(
          "Nur ein Auswahlfeld führt eine Werteliste (Feld " + field.getLabel() + ")");
    }
    return field;
  }

  private LibraryMetadataFieldValue requireValue(LibraryMetadataField field, String code) {
    return valueRepository
        .findByFieldIdAndCode(field.getId(), code)
        .orElseThrow(() -> new NotFoundException("Wert nicht gefunden: " + code));
  }

  private static void requireRetrievalEffect(boolean filter, boolean contextPrefix) {
    if (!filter && !contextPrefix) {
      throw new ValidationException(
          "Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken;"
              + " „nur Beleg-Anzeige“ genügt nicht");
    }
  }

  /**
   * A citation position is 1 or 2 and belongs to at most one field per library - the "höchstens
   * zwei Bibliotheksfelder in der Belegzeile" of the specification. Taking a position another field
   * already holds is a conflict, not a silent swap.
   */
  private Integer validatedCitationPosition(UUID libraryId, UUID ownFieldId, Integer position) {
    if (position == null) {
      return null;
    }
    if (position != 1 && position != 2) {
      throw new ValidationException("Die Zitierposition ist 1 oder 2");
    }
    for (LibraryMetadataField other :
        fieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(libraryId)) {
      if (!other.getId().equals(ownFieldId) && position.equals(other.getCitationPosition())) {
        throw new ConflictException(
            "Die Zitierposition " + position + " hat bereits das Feld " + other.getLabel());
      }
    }
    return position;
  }

  private static String requireFieldKey(String fieldKey) {
    String key = fieldKey == null ? "" : fieldKey.strip();
    if (!FIELD_KEY.matcher(key).matches() || key.length() > 50) {
      throw new ValidationException(
          "Der Feldschlüssel besteht aus Kleinbuchstaben, Ziffern und Unterstrichen"
              + " und beginnt mit einem Buchstaben");
    }
    return key;
  }

  private static String requireValueCode(String code) {
    String value = code == null ? "" : code.strip();
    if (!VALUE_CODE.matcher(value).matches() || value.length() > 50) {
      throw new ValidationException(
          "Ein Wertecode besteht aus Buchstaben, Ziffern, Punkt, Bindestrich und Unterstrich");
    }
    return value;
  }

  private static String requireLabel(String label, String what) {
    String value = label == null ? "" : label.strip();
    if (value.isEmpty()) {
      throw new ValidationException(what + " darf nicht leer sein");
    }
    if (value.length() > 100) {
      throw new ValidationException(what + " darf höchstens 100 Zeichen lang sein");
    }
    return value;
  }

  private static void requireValueCount(int count) {
    if (count > MAX_VALUES) {
      throw new ValidationException("Eine Werteliste führt höchstens " + MAX_VALUES + " Werte");
    }
  }

  /**
   * The pattern belongs to a PATTERN field and only to one; it must compile, because every value of
   * the field is checked against it.
   */
  private static String validatedPattern(LibraryMetadataFieldType type, String pattern) {
    if (type != LibraryMetadataFieldType.PATTERN) {
      if (pattern != null && !pattern.isBlank()) {
        throw new ValidationException(
            "Nur ein Feld vom Typ „Kennung nach Muster“ trägt ein Muster");
      }
      return null;
    }
    if (pattern == null || pattern.isBlank()) {
      throw new ValidationException("Ein Feld vom Typ „Kennung nach Muster“ braucht ein Muster");
    }
    String value = pattern.strip();
    if (value.length() > 200) {
      throw new ValidationException("Das Muster darf höchstens 200 Zeichen lang sein");
    }
    Pattern compiled;
    try {
      compiled = Pattern.compile(value);
    } catch (PatternSyntaxException e) {
      throw new ValidationException("Das Muster ist ungültig: " + e.getDescription());
    }
    // The management right is no trust boundary - every user may create a library and owns it - so
    // a pattern is user input and is refused here if it cannot be evaluated within the step budget
    // (BoundedRegex). Every value check runs under the same budget.
    BoundedRegex.requireEvaluableWithinBudget(value, compiled);
    return value;
  }
}
