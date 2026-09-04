package io.opaa.chat;

import io.opaa.indexing.metadata.MetadataFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persisted form of a chat's sticky {@link MetadataFilter} in {@code chats.metadata_filter}
 * (#1070): the three filter fields as a small JSON object with ISO dates, {@code null} for no
 * filter - the same shape the API carries, so a stored row reads back into the same record.
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
    return MetadataFilter.parse(
        codes,
        object.get("documentDateFrom") == null ? null : object.get("documentDateFrom").toString(),
        object.get("documentDateTo") == null ? null : object.get("documentDateTo").toString());
  }
}
