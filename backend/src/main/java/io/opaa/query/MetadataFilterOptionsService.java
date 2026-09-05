package io.opaa.query;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.DocumentMetadataValueRepository;
import io.opaa.indexing.metadata.DocumentTypeVocabulary;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.LibraryMetadataField;
import io.opaa.indexing.metadata.LibraryMetadataFieldDefinition;
import io.opaa.indexing.metadata.LibraryMetadataFieldService;
import io.opaa.indexing.metadata.MetadataFieldFill;
import io.opaa.indexing.metadata.MetadataFillCounter;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The Füllstand and the offered values of the filterable core fields for one person's search scope
 * (metadata-schema.md "Eintrittsbedingung für den Kernfeld-Filter"): the scope is resolved exactly
 * as {@link QueryService#query} resolves it - from the chat's own settings, or from {@code
 * useKnowledge}/{@code libraryIds}, always narrowed to what the caller may read - and every number
 * is counted over that scope only. No aggregate here ever exceeds the rights context of the asking
 * person; the cache in front keeps the same key.
 */
@Service
public class MetadataFilterOptionsService {

  private final QueryService queryService;
  private final ChatService chatService;
  private final LibraryAccessService libraryAccessService;
  private final DocumentRepository documentRepository;
  private final DocumentMetadataValueRepository valueRepository;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final MetadataFilterProperties properties;
  private final MetadataFilterOptionsCache cache;
  private final LibraryMetadataFieldService fieldService;
  private final MetadataFillCounter fillCounter;
  private final KnowledgeLibraryRepository libraryRepository;

  public MetadataFilterOptionsService(
      QueryService queryService,
      ChatService chatService,
      LibraryAccessService libraryAccessService,
      DocumentRepository documentRepository,
      DocumentMetadataValueRepository valueRepository,
      DocumentTypeVocabularyRepository vocabularyRepository,
      MetadataFilterProperties properties,
      MetadataFilterOptionsCache cache,
      LibraryMetadataFieldService fieldService,
      MetadataFillCounter fillCounter,
      KnowledgeLibraryRepository libraryRepository) {
    this.queryService = queryService;
    this.chatService = chatService;
    this.libraryAccessService = libraryAccessService;
    this.documentRepository = documentRepository;
    this.valueRepository = valueRepository;
    this.vocabularyRepository = vocabularyRepository;
    this.properties = properties;
    this.cache = cache;
    this.fieldService = fieldService;
    this.fillCounter = fillCounter;
    this.libraryRepository = libraryRepository;
  }

  /**
   * The options for the scope the caller's next question would search: the named chat's scope when
   * {@code chatId} names a chat the caller authored, otherwise the request-level scope.
   */
  public MetadataFilterOptions optionsFor(
      CurrentUser caller, UUID chatId, boolean useKnowledge, List<UUID> requestedLibraryIds) {
    Optional<Chat> chat = chatService.findOwnedChat(chatId, caller.id());
    Set<UUID> readable =
        libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId());
    Set<UUID> scope =
        queryService.resolveSearchScope(chat, useKnowledge, requestedLibraryIds, readable);
    return optionsForScope(caller.id(), scope);
  }

  /**
   * The options over a scope the caller already resolved - taken as given, like the pipeline takes
   * its search scope; cached per person and scope.
   */
  public MetadataFilterOptions optionsForScope(UUID userId, Set<UUID> searchScope) {
    return cache.get(userId, searchScope, this::compute);
  }

  /** Four independent read queries over the scope; nothing here needs one transaction. */
  MetadataFilterOptions compute(Set<UUID> scope) {
    if (scope.isEmpty()) {
      return new MetadataFilterOptions(0, fields(0, 0, 0), List.of(), null, null, List.of());
    }
    long total = documentRepository.countByLibraryIdInAndStatus(scope, DocumentStatus.INDEXED);
    long typeFilled = 0;
    long dateFilled = 0;
    for (DocumentMetadataValueRepository.FieldStateCount count :
        valueRepository.countByFieldAndStateInLibraries(scope, DocumentStatus.INDEXED)) {
      // A value and the mark "kein Wert ermittelbar" both count as answered for the Füllstand.
      if (CoreMetadataField.DOCUMENT_TYPE.key().equals(count.getFieldKey())) {
        typeFilled += count.getDocumentCount();
      } else if (CoreMetadataField.DOCUMENT_DATE.key().equals(count.getFieldKey())) {
        dateFilled += count.getDocumentCount();
      }
    }
    DocumentTypeVocabulary vocabulary = vocabularyRepository.snapshot();
    List<MetadataFilterOptions.DocumentTypeOption> types = new ArrayList<>();
    for (DocumentMetadataValueRepository.VocabularyCodeCount count :
        valueRepository.countByVocabularyCodeInLibraries(
            scope, DocumentStatus.INDEXED, CoreMetadataField.DOCUMENT_TYPE.key())) {
      types.add(
          new MetadataFilterOptions.DocumentTypeOption(
              count.getCode(),
              vocabulary.labelOf(count.getCode()).orElse(count.getCode()),
              count.getDocumentCount()));
    }
    DocumentMetadataValueRepository.DateSpan span =
        valueRepository.dateSpanInLibraries(
            scope, DocumentStatus.INDEXED, CoreMetadataField.DOCUMENT_DATE.key());
    return new MetadataFilterOptions(
        total,
        fields(total, typeFilled, dateFilled),
        types,
        span == null ? null : span.getMinDate(),
        span == null ? null : span.getMaxDate(),
        libraryFields(scope));
  }

  /**
   * The filterable library fields of the scope. Every figure is counted over the field's own
   * library within the scope, and the listed values are the ones the caller's documents actually
   * carry - never the configured list, which a client reads from the library's schema
   * (metadata-schema.md, Rechte-Invariante).
   */
  private List<MetadataFilterOptions.LibraryFieldOption> libraryFields(Set<UUID> scope) {
    Map<UUID, List<LibraryMetadataFieldDefinition>> byLibrary =
        fieldService.fieldsOfLibraries(scope);
    if (byLibrary.isEmpty()) {
      return List.of();
    }
    Map<UUID, String> names = new HashMap<>();
    libraryRepository
        .findAllById(byLibrary.keySet())
        .forEach(library -> names.put(library.getId(), library.getName()));
    Map<UUID, Map<String, MetadataFieldFill>> fills =
        fillCounter.countFor(
            byLibrary.keySet(),
            byLibrary.values().stream()
                .flatMap(List::stream)
                .map(definition -> definition.field().documentFieldKey())
                .distinct()
                .toList());

    List<MetadataFilterOptions.LibraryFieldOption> options = new ArrayList<>();
    for (Map.Entry<UUID, List<LibraryMetadataFieldDefinition>> entry : byLibrary.entrySet()) {
      UUID libraryId = entry.getKey();
      Set<UUID> one = Set.of(libraryId);
      for (LibraryMetadataFieldDefinition definition : entry.getValue()) {
        LibraryMetadataField field = definition.field();
        if (!field.isFilterEnabled()) {
          continue;
        }
        MetadataFieldFill fill =
            fills
                .getOrDefault(libraryId, Map.of())
                .getOrDefault(field.documentFieldKey(), MetadataFieldFill.EMPTY);
        List<MetadataFilterOptions.LibraryFieldValueOption> values = new ArrayList<>();
        LocalDate min = null;
        LocalDate max = null;
        if (field.getType() == LibraryMetadataFieldType.DATE) {
          DocumentMetadataValueRepository.DateSpan fieldSpan =
              valueRepository.dateSpanInLibraries(
                  one, DocumentStatus.INDEXED, field.documentFieldKey());
          min = fieldSpan == null ? null : fieldSpan.getMinDate();
          max = fieldSpan == null ? null : fieldSpan.getMaxDate();
        } else {
          Map<String, String> labels = new HashMap<>();
          definition.values().forEach(value -> labels.put(value.getCode(), value.getLabel()));
          for (DocumentMetadataValueRepository.VocabularyCodeCount count :
              valueRepository.countByLibraryFieldValueInLibraries(
                  one, DocumentStatus.INDEXED, field.documentFieldKey())) {
            values.add(
                new MetadataFilterOptions.LibraryFieldValueOption(
                    count.getCode(),
                    labels.getOrDefault(count.getCode(), count.getCode()),
                    count.getDocumentCount()));
          }
        }
        options.add(
            new MetadataFilterOptions.LibraryFieldOption(
                libraryId,
                names.getOrDefault(libraryId, ""),
                field.getFieldKey(),
                field.getLabel(),
                field.getType(),
                fill.filledDocuments() + fill.notDeterminableDocuments(),
                fill.totalDocuments(),
                properties.libraryFieldOfferThreshold(),
                values,
                min,
                max));
      }
    }
    return options;
  }

  private List<MetadataFilterOptions.FieldOption> fields(
      long total, long typeFilled, long dateFilled) {
    return List.of(
        new MetadataFilterOptions.FieldOption(
            CoreMetadataField.DOCUMENT_TYPE,
            typeFilled,
            total,
            properties.documentTypeOfferThreshold()),
        new MetadataFilterOptions.FieldOption(
            CoreMetadataField.DOCUMENT_DATE,
            dateFilled,
            total,
            properties.documentDateOfferThreshold()));
  }
}
