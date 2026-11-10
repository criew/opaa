package io.opaa.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persisted form of a template's placeholders in {@code document_templates.placeholders}: a
 * JSON array of objects with {@code name}, {@code kind} and {@code fields}.
 */
final class PlaceholdersJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<List<Map<String, Object>>> ARRAY = new TypeReference<>() {};

  private PlaceholdersJson() {}

  static String write(List<TemplatePlaceholder> placeholders) {
    List<Map<String, Object>> json = new ArrayList<>();
    for (TemplatePlaceholder placeholder : placeholders) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", placeholder.name());
      entry.put("kind", placeholder.kind().name());
      entry.put("fields", placeholder.fields());
      json.add(entry);
    }
    return MAPPER.writeValueAsString(json);
  }

  static List<TemplatePlaceholder> read(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    List<TemplatePlaceholder> placeholders = new ArrayList<>();
    for (Map<String, Object> entry : MAPPER.readValue(json, ARRAY)) {
      List<String> fields = new ArrayList<>();
      if (entry.get("fields") instanceof List<?> list) {
        list.forEach(field -> fields.add(String.valueOf(field)));
      }
      placeholders.add(
          new TemplatePlaceholder(
              (String) entry.get("name"),
              PlaceholderKind.valueOf((String) entry.get("kind")),
              fields));
    }
    return List.copyOf(placeholders);
  }
}
