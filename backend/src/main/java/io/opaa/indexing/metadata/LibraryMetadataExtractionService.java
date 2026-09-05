package io.opaa.indexing.metadata;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.llm.ActiveChatModelResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's side of the model-backed extraction (#1073): the two switches, the Extraktionsgüte
 * and the Stichproben-Export. Changing a switch is a schema decision and needs the management right
 * at the library; reading the Güte needs no more than the right to read it, exactly like the
 * Pflege-Anker. Every figure is counted when asked, over the one library the caller may read
 * (metadata-schema.md, Rechte-Invariante).
 */
@Service
public class LibraryMetadataExtractionService {

  /** Upper bound of the Stichprobe; the specification fixes 100, a client may ask for less. */
  public static final int MAX_SAMPLE_SIZE = 500;

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final LibraryMetadataFieldRepository fieldRepository;
  private final DocumentMetadataValueRepository valueRepository;
  private final DocumentRepository documentRepository;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final ModelExtractionCounters counters;
  private final ActiveChatModelResolver chatModelResolver;

  public LibraryMetadataExtractionService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      LibraryMetadataFieldRepository fieldRepository,
      DocumentMetadataValueRepository valueRepository,
      DocumentRepository documentRepository,
      DocumentTypeVocabularyRepository vocabularyRepository,
      ModelExtractionCounters counters,
      ActiveChatModelResolver chatModelResolver) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.fieldRepository = fieldRepository;
    this.valueRepository = valueRepository;
    this.documentRepository = documentRepository;
    this.vocabularyRepository = vocabularyRepository;
    this.counters = counters;
    this.chatModelResolver = chatModelResolver;
  }

  /** The switches of {@code libraryId} with the chat role they would use; management right. */
  @Transactional(readOnly = true)
  public LibraryExtractionSettings settingsOf(UUID libraryId, CurrentUser caller) {
    return settingsOf(requireLibrary(libraryId, caller, AssetRole.MANAGER));
  }

  /**
   * Switches the model-backed extraction and the freie Schlagworte on or off. Stored values stay as
   * they are: switching off stops the next call, it does not erase what earlier calls found.
   */
  @Transactional
  public LibraryExtractionSettings updateSettings(
      UUID libraryId, boolean modelExtractionEnabled, boolean keywordsEnabled, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    library.setModelExtractionSwitches(modelExtractionEnabled, keywordsEnabled);
    return settingsOf(libraryRepository.save(library));
  }

  /**
   * The Extraktionsgüte of {@code libraryId}: per field, how many of the library's indexed
   * documents carry a deterministic, a model-derived, a manual or no value at all - plus the
   * Zählwerk. Reading needs {@link AssetRole#VIEWER}.
   */
  @Transactional(readOnly = true)
  public LibraryMetadataQuality qualityOf(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.VIEWER);
    Map<String, String> labelsByKey = labelsOf(library.getId());
    long total =
        documentRepository.countByLibraryIdAndStatus(library.getId(), DocumentStatus.INDEXED);

    Map<String, long[]> countsByField = new HashMap<>();
    for (DocumentMetadataValueRepository.FieldOriginStateCount count :
        valueRepository.countByFieldOriginAndState(library.getId(), DocumentStatus.INDEXED)) {
      long[] slot = countsByField.computeIfAbsent(count.getFieldKey(), key -> new long[4]);
      if (count.getState() == MetadataValueState.NOT_DETERMINABLE) {
        slot[3] += count.getDocumentCount();
      } else if (count.getOrigin() == MetadataOrigin.DERIVED) {
        slot[1] += count.getDocumentCount();
      } else if (count.getOrigin() == MetadataOrigin.MANUAL) {
        slot[2] += count.getDocumentCount();
      } else {
        slot[0] += count.getDocumentCount();
      }
    }
    List<MetadataFieldQuality> fields = new ArrayList<>();
    labelsByKey.forEach(
        (key, label) -> {
          long[] slot = countsByField.getOrDefault(key, new long[4]);
          fields.add(
              new MetadataFieldQuality(key, label, total, slot[0], slot[1], slot[2], slot[3]));
        });
    return new LibraryMetadataQuality(
        library.getId(),
        total,
        library.isModelExtractionEnabled(),
        library.isKeywordsEnabled(),
        ModelMetadataExtractor.CONFIDENCE_THRESHOLD,
        List.copyOf(fields),
        counters.statsFor(library.getId()));
  }

  /**
   * The Stichprobe of {@code size} documents with every stored value and its provenance - the input
   * of the handausgewertete Auswertung. Management right: it exports document titles and values in
   * bulk, which is more than reading the library's Güte.
   */
  @Transactional(readOnly = true)
  public LibraryMetadataSample sampleOf(UUID libraryId, int size, CurrentUser caller) {
    KnowledgeLibrary library = requireLibrary(libraryId, caller, AssetRole.MANAGER);
    int capped = Math.clamp(size, 1, MAX_SAMPLE_SIZE);
    List<Document> documents =
        documentRepository.findSampleByLibraryId(
            library.getId(), DocumentStatus.INDEXED, PageRequest.of(0, capped));
    Map<String, String> labelsByKey = labelsOf(library.getId());
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    Map<UUID, List<DocumentMetadataValue>> rowsByDocument = new LinkedHashMap<>();
    if (!documents.isEmpty()) {
      for (DocumentMetadataValue row :
          valueRepository.findByDocumentIdIn(documents.stream().map(Document::getId).toList())) {
        rowsByDocument.computeIfAbsent(row.getDocumentId(), id -> new ArrayList<>()).add(row);
      }
    }
    List<MetadataSampleDocument> sampled = new ArrayList<>();
    for (Document document : documents) {
      List<DocumentMetadataValue> rows = rowsByDocument.getOrDefault(document.getId(), List.of());
      List<MetadataSampleValue> values = new ArrayList<>();
      String title = null;
      for (DocumentMetadataValue row : rows) {
        if (CoreMetadataField.TITLE.key().equals(row.getFieldKey())) {
          title = row.getTextValue();
        }
        values.add(
            new MetadataSampleValue(
                row.getFieldKey(),
                labelsByKey.getOrDefault(row.getFieldKey(), row.getFieldKey()),
                displayValueOf(row, vocabulary),
                row.getOrigin(),
                row.getConfidence(),
                row.getModelId()));
      }
      sampled.add(
          new MetadataSampleDocument(
              document.getId(), document.getFileName(), title, List.copyOf(values)));
    }
    return new LibraryMetadataSample(library.getId(), capped, List.copyOf(sampled));
  }

  /** The label of a value as a person reads it - a vocabulary code resolves to its German label. */
  private static String displayValueOf(
      DocumentMetadataValue row, DocumentTypeVocabulary vocabulary) {
    if (row.getState() == MetadataValueState.NOT_DETERMINABLE) {
      return null;
    }
    if (row.getVocabularyCode() != null) {
      return vocabulary.labelOf(row.getVocabularyCode()).orElse(row.getVocabularyCode());
    }
    if (row.getDateValue() != null) {
      return row.getDateValue().toString();
    }
    return row.getTextValue();
  }

  /** Core fields, then the library's own fields, in schema order - the display order everywhere. */
  private Map<String, String> labelsOf(UUID libraryId) {
    Map<String, String> labelsByKey = new LinkedHashMap<>();
    for (CoreMetadataField field : CoreMetadataField.values()) {
      labelsByKey.put(field.key(), field.label());
    }
    for (LibraryMetadataField field :
        fieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(libraryId)) {
      labelsByKey.put(field.documentFieldKey(), field.getLabel());
    }
    return labelsByKey;
  }

  private LibraryExtractionSettings settingsOf(KnowledgeLibrary library) {
    ChatRoleSummary chatRole;
    try {
      chatRole = ChatRoleSummary.of(chatModelResolver.resolveDescription());
    } catch (RuntimeException e) {
      chatRole = null;
    }
    return new LibraryExtractionSettings(
        library.getId(),
        library.isModelExtractionEnabled(),
        library.isKeywordsEnabled(),
        ModelMetadataExtractor.CONFIDENCE_THRESHOLD,
        chatRole);
  }

  private KnowledgeLibrary requireLibrary(UUID libraryId, CurrentUser caller, AssetRole role) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> candidate.getOrganizationId().equals(caller.organizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), role);
    return library;
  }
}
