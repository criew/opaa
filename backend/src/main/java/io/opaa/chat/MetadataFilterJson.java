package io.opaa.chat;

import io.opaa.indexing.metadata.FormatFieldCondition;
import io.opaa.indexing.metadata.LibraryFieldCondition;
import io.opaa.indexing.metadata.MetadataFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persisted form of a chat's sticky {@link MetadataFilter} in {@code chats.metadata_filter} (um
 * Bibliotheksfelder erweitert in): the filter's conditions as a small JSON object with ISO dates,
 * {@code null} for no filter - the same shape the API carries, so a stored row reads back into the
 * same record.
 */
final class MetadataFilterJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private MetadataFilterJson() {}

  /** {@code null} for an absent or empty filter - the column then means "no filter". */
  static String write(MetadataFilter filter) {
    if (filter == null || filter.isEmpty()) {
      return null;
    }
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("documentTypes", filter.documentTypes().stream().sorted().toList());
    json.put(
        "documentDateFrom",
        filter.documentDateFrom() == null ? null : filter.documentDateFrom().toString());
    json.put(
        "documentDateTo",
        filter.documentDateTo() == null ? null : filter.documentDateTo().toString());
    List<Map<String, Object>> libraryFields = new ArrayList<>();
    for (LibraryFieldCondition condition : filter.libraryFields()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("libraryId", condition.libraryId().toString());
      entry.put("fieldKey", condition.fieldKey());
      entry.put("codes", condition.codes().stream().sorted().toList());
      entry.put("dateFrom", condition.dateFrom() == null ? null : condition.dateFrom().toString());
      entry.put("dateTo", condition.dateTo() == null ? null : condition.dateTo().toString());
      entry.put("value", condition.value());
      libraryFields.add(entry);
    }
    List<Map<String, Object>> formatFields = new ArrayList<>();
    for (FormatFieldCondition condition : filter.formatFields()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("fieldKey", condition.fieldKey());
      entry.put("values", condition.values().stream().sorted().toList());
      formatFields.add(entry);
    }
    if (!formatFields.isEmpty()) {
      // Omitted when empty, for the same reason libraryFields is: a row written before #1242
      // reads back identically.
      json.put("formatFields", formatFields);
    }
    if (!libraryFields.isEmpty()) {
      // Omitted when empty, so a filter on core fields alone keeps the exact shape it had before
      //  - a stored row written by an older release reads back identically either way.
      json.put("libraryFields", libraryFields);
    }
    return MAPPER.writeValueAsString(json);
  }

  /** {@link MetadataFilter#NONE} for a {@code null} or blank column. */
  @SuppressWarnings("unchecked")
  static MetadataFilter read(String json) {
    if (json == null || json.isBlank()) {
      return MetadataFilter.NONE;
    }
    Map<String, Object> object = MAPPER.readValue(json, OBJECT);
    List<String> codes = new ArrayList<>();
    Object types = object.get("documentTypes");
    if (types instanceof List<?> list) {
      list.forEach(code -> codes.add(String.valueOf(code)));
    }
    List<LibraryFieldCondition> conditions = new ArrayList<>();
    if (object.get("libraryFields") instanceof List<?> entries) {
      for (Object entry : entries) {
        if (entry instanceof Map<?, ?> map) {
          List<String> entryCodes = new ArrayList<>();
          if (map.get("codes") instanceof List<?> list) {
            list.forEach(code -> entryCodes.add(String.valueOf(code)));
          }
          conditions.add(
              LibraryFieldCondition.parse(
                  UUID.fromString(String.valueOf(map.get("libraryId"))),
                  String.valueOf(map.get("fieldKey")),
                  entryCodes,
                  text(map.get("dateFrom")),
                  text(map.get("dateTo")),
                  text(map.get("value"))));
        }
      }
    }
    List<FormatFieldCondition> formatConditions = new ArrayList<>();
    if (object.get("formatFields") instanceof List<?> entries) {
      for (Object entry : entries) {
        if (entry instanceof Map<?, ?> map) {
          List<String> values = new ArrayList<>();
          if (map.get("values") instanceof List<?> list) {
            list.forEach(value -> values.add(String.valueOf(value)));
          }
          formatConditions.add(
              FormatFieldCondition.parse(String.valueOf(map.get("fieldKey")), values));
        }
      }
    }
    return MetadataFilter.parse(
            codes,
            object.get("documentDateFrom") == null
                ? null
                : object.get("documentDateFrom").toString(),
            object.get("documentDateTo") == null ? null : object.get("documentDateTo").toString())
        .withLibraryFields(conditions)
        .withFormatFields(formatConditions);
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
