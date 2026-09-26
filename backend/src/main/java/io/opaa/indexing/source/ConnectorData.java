package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * An immutable JSON object whose shape one connector defines - its settings, the query of a listing
 * or the findings of a connection test (ADR-0038). The core hands it through without knowing a key;
 * values are JSON-like: {@code String}, {@code Number}, {@code Boolean}, {@code List}, {@code Map}
 * or {@code null}.
 */
public final class ConnectorData {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final Map<String, Object> values;

  private ConnectorData(Map<String, Object> values) {
    this.values = values;
  }

  /** A deep, unmodifiable copy of {@code values}, keeping their order. */
  public static ConnectorData of(Map<String, ?> values) {
    return new ConnectorData(copyObject(values));
  }

  /**
   * The settings stored on {@code library}, {@code null} when it carries none.
   *
   * @throws IllegalArgumentException when the stored text is no JSON object
   */
  public static ConnectorData storedIn(KnowledgeLibrary library) {
    return fromJson(library.getSourceSettings());
  }

  /**
   * {@code null} for a {@code null} or blank text.
   *
   * @throws IllegalArgumentException when {@code json} is no JSON object
   */
  public static ConnectorData fromJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return of(MAPPER.readValue(json, OBJECT));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("connector data is no JSON object", e);
    }
  }

  public String toJson() {
    return MAPPER.writeValueAsString(values);
  }

  public Map<String, Object> asMap() {
    return values;
  }

  public boolean has(String key) {
    return values.containsKey(key);
  }

  public Object get(String key) {
    return values.get(key);
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }

  private static Map<String, Object> copyObject(Map<?, ?> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(String.valueOf(key), copyValue(value)));
    return Collections.unmodifiableMap(copy);
  }

  private static Object copyValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return copyObject(map);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      list.forEach(item -> copy.add(copyValue(item)));
      return Collections.unmodifiableList(copy);
    }
    if (value instanceof ConnectorData data) {
      return data.values;
    }
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ConnectorData data && values.equals(data.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return "ConnectorData" + values;
  }
}
