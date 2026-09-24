package io.opaa.prompt;

import io.opaa.api.types.PromptVariableType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persisted form of a prompt's variables in {@code prompts.variables}: a JSON array of objects
 * with {@code name}, {@code label}, {@code type}, {@code required}, {@code defaultValue} and {@code
 * options} - the shape the API carries. Only validated definitions are written.
 */
final class PromptVariablesJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<List<Map<String, Object>>> ARRAY = new TypeReference<>() {};

  private PromptVariablesJson() {}

  static String write(List<PromptVariable> variables) {
    List<Map<String, Object>> json = new ArrayList<>();
    for (PromptVariable variable : variables) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", variable.name());
      entry.put("label", variable.label());
      entry.put("type", variable.type().name());
      entry.put("required", variable.required());
      entry.put("defaultValue", variable.defaultValue());
      entry.put("options", variable.options());
      json.add(entry);
    }
    return MAPPER.writeValueAsString(json);
  }

  static List<PromptVariable> read(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    List<PromptVariable> variables = new ArrayList<>();
    for (Map<String, Object> entry : MAPPER.readValue(json, ARRAY)) {
      variables.add(
          new PromptVariable(
              (String) entry.get("name"),
              (String) entry.get("label"),
              PromptVariableType.valueOf((String) entry.get("type")),
              Boolean.TRUE.equals(entry.get("required")),
              (String) entry.get("defaultValue"),
              strings(entry.get("options"))));
    }
    return List.copyOf(variables);
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> strings = new ArrayList<>(list.size());
    for (Object item : list) {
      strings.add(item == null ? null : item.toString());
    }
    return strings;
  }
}
