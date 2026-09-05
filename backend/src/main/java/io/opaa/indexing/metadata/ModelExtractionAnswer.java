package io.opaa.indexing.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parsed answer of one model call: per asked field a proposed value with its confidence, plus
 * the freie Schlagworte. Unparsable or structurally unusable output yields {@link #EMPTY} rather
 * than an exception - an unusable answer is the same case as no answer, and neither blocks the
 * ingest.
 */
public record ModelExtractionAnswer(Map<String, ProposedValue> values, List<String> keywords) {

  /** A value the model proposed; {@code value} is {@code null} when it answered "no value". */
  public record ProposedValue(String value, Double confidence) {}

  public static final ModelExtractionAnswer EMPTY = new ModelExtractionAnswer(Map.of(), List.of());

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Reads the answer out of {@code raw}, tolerating what models add around JSON despite the
   * instruction not to: a fenced code block, a leading sentence, trailing text. Everything outside
   * the outermost braces is ignored.
   */
  public static ModelExtractionAnswer parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return EMPTY;
    }
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return EMPTY;
    }
    JsonNode root;
    try {
      root = JSON.readTree(raw.substring(start, end + 1));
    } catch (JacksonException e) {
      return EMPTY;
    }
    if (root == null || !root.isObject()) {
      return EMPTY;
    }
    return new ModelExtractionAnswer(
        readValues(root.get("fields")), readKeywords(root.get("keywords")));
  }

  /** The proposal for {@code fieldKey}, absent when the model said nothing about it. */
  public Optional<ProposedValue> valueOf(String fieldKey) {
    return Optional.ofNullable(values.get(fieldKey));
  }

  private static Map<String, ProposedValue> readValues(JsonNode fields) {
    Map<String, ProposedValue> values = new LinkedHashMap<>();
    if (fields == null || !fields.isObject()) {
      return values;
    }
    for (Map.Entry<String, JsonNode> entry : fields.properties()) {
      JsonNode node = entry.getValue();
      if (node == null || !node.isObject()) {
        continue;
      }
      JsonNode value = node.get("value");
      JsonNode confidence = node.get("confidence");
      String text = value == null || value.isNull() ? null : value.asString().trim();
      Double score = confidence == null || !confidence.isNumber() ? null : confidence.asDouble();
      values.put(
          entry.getKey(), new ProposedValue(text == null || text.isEmpty() ? null : text, score));
    }
    return values;
  }

  private static List<String> readKeywords(JsonNode keywords) {
    List<String> result = new ArrayList<>();
    if (keywords == null || !keywords.isArray()) {
      return result;
    }
    for (JsonNode node : keywords) {
      if (!node.isString()) {
        continue;
      }
      String keyword = node.asString().trim();
      if (!keyword.isEmpty()) {
        result.add(keyword);
      }
    }
    return result;
  }
}
