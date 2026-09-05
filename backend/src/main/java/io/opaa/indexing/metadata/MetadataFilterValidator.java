package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.common.ValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks a {@link MetadataFilter} against the schema before it is stored on a chat or applied to a
 * question (#1070/#1071): an unknown Dokumentart code, an unknown library field, a condition whose
 * shape does not fit the field's type and a SELECT code outside the field's configured list are
 * caller errors (400). Nothing is mapped to a near match - a filter on a value no document can
 * carry would silently keep exactly the documents without one.
 *
 * <p>A library field is resolved only within the libraries the caller may read. A condition naming
 * any other library is rejected with the same message an unknown field gets, so the answer does not
 * reveal which fields a foreign library defines. It could not have narrowed anything either: the
 * metadata filter is subordinate to the permission filter.
 */
@Component
public class MetadataFilterValidator {

  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final LibraryMetadataFieldRepository fieldRepository;
  private final LibraryMetadataFieldValueRepository valueRepository;

  public MetadataFilterValidator(
      DocumentTypeVocabularyRepository vocabularyRepository,
      LibraryMetadataFieldRepository fieldRepository,
      LibraryMetadataFieldValueRepository valueRepository) {
    this.vocabularyRepository = vocabularyRepository;
    this.fieldRepository = fieldRepository;
    this.valueRepository = valueRepository;
  }

  /** The filter itself when every condition is valid; a {@link ValidationException} otherwise. */
  @Transactional(readOnly = true)
  public MetadataFilter validate(MetadataFilter filter, Set<UUID> readableLibraryIds) {
    if (filter == null || filter.isEmpty()) {
      return filter;
    }
    filter.validatedAgainst(vocabularyRepository.snapshot());
    if (!filter.filtersLibraryFields()) {
      return filter;
    }
    Set<UUID> libraryIds = new HashSet<>();
    for (LibraryFieldCondition condition : filter.libraryFields()) {
      if (!readableLibraryIds.contains(condition.libraryId())) {
        throw unknownField(condition.fieldKey());
      }
      libraryIds.add(condition.libraryId());
    }
    Map<String, LibraryMetadataField> byIdentity = new java.util.HashMap<>();
    for (LibraryMetadataField field :
        fieldRepository.findByLibraryIdInOrderBySortOrderAscFieldKeyAsc(libraryIds)) {
      byIdentity.put(identity(field.getLibraryId(), field.getFieldKey()), field);
    }
    List<LibraryFieldCondition> validated = new ArrayList<>();
    for (LibraryFieldCondition condition : filter.libraryFields()) {
      LibraryMetadataField field =
          byIdentity.get(identity(condition.libraryId(), condition.fieldKey()));
      if (field == null) {
        throw unknownField(condition.fieldKey());
      }
      if (!field.isFilterEnabled()) {
        throw new ValidationException(
            "Das Feld " + field.getLabel() + " wirkt nicht im Filter dieser Bibliothek");
      }
      if (condition.type() != field.getType()) {
        throw new ValidationException(
            "Die Bedingung passt nicht zum Typ des Feldes " + field.getLabel());
      }
      if (field.getType() == LibraryMetadataFieldType.SELECT) {
        Set<String> codes = new HashSet<>();
        valueRepository
            .findByFieldIdOrderBySortOrderAscCodeAsc(field.getId())
            .forEach(value -> codes.add(value.getCode()));
        for (String code : condition.codes()) {
          if (!codes.contains(code)) {
            throw new ValidationException(
                "Unbekannter Wert im Filter auf " + field.getLabel() + ": " + code);
          }
        }
      }
      validated.add(condition);
    }
    return filter.withLibraryFields(validated);
  }

  private static ValidationException unknownField(String fieldKey) {
    return new ValidationException("Unbekanntes Bibliotheksfeld im Metadatenfilter: " + fieldKey);
  }

  private static String identity(UUID libraryId, String fieldKey) {
    return libraryId + "/" + fieldKey;
  }
}
