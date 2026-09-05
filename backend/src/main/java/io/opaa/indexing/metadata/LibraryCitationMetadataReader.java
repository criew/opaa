package io.opaa.indexing.metadata;

import io.opaa.indexing.Document;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library-field half of a Beleg (#1071, metadata-schema.md Wirkstelle 3): per document, the
 * values of its library's fields that carry a citation position, in that position's order and at
 * most two - the Belegzeile stays a line one can read in the flow of an answer. An empty field
 * produces no entry at all.
 *
 * <p>Two queries for a whole answer's sources, not one per document: the citation fields of the
 * involved libraries and the values of the involved documents.
 */
@Component
public class LibraryCitationMetadataReader {

  /** metadata-schema.md: "höchstens zwei Bibliotheksfelder" beside the core fields. */
  static final int MAX_CITATION_FIELDS = 2;

  private final LibraryMetadataFieldRepository fieldRepository;
  private final LibraryMetadataFieldValueRepository valueRepository;
  private final DocumentMetadataValueRepository documentValueRepository;

  public LibraryCitationMetadataReader(
      LibraryMetadataFieldRepository fieldRepository,
      LibraryMetadataFieldValueRepository valueRepository,
      DocumentMetadataValueRepository documentValueRepository) {
    this.fieldRepository = fieldRepository;
    this.valueRepository = valueRepository;
    this.documentValueRepository = documentValueRepository;
  }

  /** The citation entries of every document in {@code documents}; absent for a document without. */
  @Transactional(readOnly = true)
  public Map<UUID, List<CitationFieldValue>> forDocuments(Collection<Document> documents) {
    Map<UUID, List<CitationFieldValue>> result = new LinkedHashMap<>();
    Set<UUID> libraryIds = new LinkedHashSet<>();
    Set<UUID> documentIds = new LinkedHashSet<>();
    for (Document document : documents) {
      if (document.getLibraryId() != null) {
        libraryIds.add(document.getLibraryId());
        documentIds.add(document.getId());
      }
    }
    if (libraryIds.isEmpty()) {
      return result;
    }
    Map<UUID, List<LibraryMetadataField>> citationFields = new HashMap<>();
    for (LibraryMetadataField field :
        fieldRepository.findByLibraryIdInOrderBySortOrderAscFieldKeyAsc(libraryIds)) {
      if (field.getCitationPosition() != null) {
        citationFields.computeIfAbsent(field.getLibraryId(), id -> new ArrayList<>()).add(field);
      }
    }
    if (citationFields.isEmpty()) {
      return result;
    }
    citationFields
        .values()
        .forEach(
            fields -> fields.sort(Comparator.comparing(LibraryMetadataField::getCitationPosition)));

    Map<String, String> valueLabels = valueLabels(citationFields);
    Map<UUID, Map<String, DocumentMetadataValue>> rowsByDocument = new HashMap<>();
    for (DocumentMetadataValue row : documentValueRepository.findByDocumentIdIn(documentIds)) {
      rowsByDocument
          .computeIfAbsent(row.getDocumentId(), id -> new HashMap<>())
          .put(row.getFieldKey(), row);
    }
    for (Document document : documents) {
      List<LibraryMetadataField> fields =
          citationFields.getOrDefault(document.getLibraryId(), List.of());
      List<CitationFieldValue> entries = new ArrayList<>();
      for (LibraryMetadataField field : fields) {
        if (entries.size() >= MAX_CITATION_FIELDS) {
          break;
        }
        DocumentMetadataValue row =
            rowsByDocument.getOrDefault(document.getId(), Map.of()).get(field.documentFieldKey());
        if (row == null || row.getState() != MetadataValueState.SET) {
          continue;
        }
        if (row.getDateValue() != null) {
          entries.add(
              new CitationFieldValue(
                  field.documentFieldKey(),
                  field.getLabel(),
                  row.getDateValue().toString(),
                  MetadataValueDisplay.displayDate(row.getDateValue(), row.getDatePrecision()),
                  row.getOrigin(),
                  row.getDatePrecision()));
        } else if (row.getTextValue() != null) {
          entries.add(
              new CitationFieldValue(
                  field.documentFieldKey(),
                  field.getLabel(),
                  row.getTextValue(),
                  valueLabels.getOrDefault(
                      field.getId() + "/" + row.getTextValue(), row.getTextValue()),
                  row.getOrigin(),
                  null));
        }
      }
      if (!entries.isEmpty()) {
        result.put(document.getId(), entries);
      }
    }
    return result;
  }

  private Map<String, String> valueLabels(Map<UUID, List<LibraryMetadataField>> citationFields) {
    List<UUID> fieldIds =
        citationFields.values().stream()
            .flatMap(List::stream)
            .map(LibraryMetadataField::getId)
            .toList();
    Map<String, String> labels = new HashMap<>();
    if (fieldIds.isEmpty()) {
      return labels;
    }
    for (LibraryMetadataFieldValue value :
        valueRepository.findByFieldIdInOrderBySortOrderAscCodeAsc(fieldIds)) {
      labels.put(value.getFieldId() + "/" + value.getCode(), value.getLabel());
    }
    return labels;
  }
}
