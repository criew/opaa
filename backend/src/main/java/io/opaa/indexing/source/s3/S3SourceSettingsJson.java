package io.opaa.indexing.source.s3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persisted form of {@link S3SourceSettings} in {@code knowledge_libraries.source_settings}
 * (ADR-0027, Entscheidung 1): one JSON object with {@code region}, {@code pathStyle}, {@code
 * scopes} (objects with {@code bucket} and {@code prefix}), {@code includePatterns} and {@code
 * excludePatterns} - the same shape the API carries, validated by the record on the way back in.
 * The database's own {@code CHECK} guards only that {@code scopes} is a non-empty array.
 */
public final class S3SourceSettingsJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private S3SourceSettingsJson() {}

  public static String write(S3SourceSettings settings) {
    if (settings == null) {
      return null;
    }
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("region", settings.region());
    json.put("pathStyle", settings.pathStyle());
    List<Map<String, Object>> scopes = new ArrayList<>();
    for (S3Scope scope : settings.scopes()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("bucket", scope.bucket());
      entry.put("prefix", scope.prefix());
      scopes.add(entry);
    }
    json.put("scopes", scopes);
    json.put("includePatterns", settings.includePatterns());
    json.put("excludePatterns", settings.excludePatterns());
    return MAPPER.writeValueAsString(json);
  }

  /**
   * {@code null} for a {@code null} or blank column.
   *
   * @throws S3SourceSettings.InvalidS3SourceSettingsException when the stored document does not
   *     read back into a valid configuration
   */
  public static S3SourceSettings read(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    Map<String, Object> object;
    try {
      object = MAPPER.readValue(json, OBJECT);
    } catch (RuntimeException e) {
      throw new S3SourceSettings.InvalidS3SourceSettingsException(
          "Die gespeicherte S3-Konfiguration ist kein gültiges JSON-Objekt.");
    }
    List<S3Scope> scopes = new ArrayList<>();
    if (object.get("scopes") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          scopes.add(S3Scope.of(string(map.get("bucket")), string(map.get("prefix"))));
        }
      }
    }
    return new S3SourceSettings(
        string(object.get("region")),
        Boolean.TRUE.equals(object.get("pathStyle")),
        scopes,
        strings(object.get("includePatterns")),
        strings(object.get("excludePatterns")));
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static List<String> strings(Object value) {
    List<String> result = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          result.add(item.toString());
        }
      }
    }
    return result;
  }
}
