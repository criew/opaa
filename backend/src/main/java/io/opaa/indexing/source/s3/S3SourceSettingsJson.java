package io.opaa.indexing.source.s3;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The persisted form of {@link S3SourceSettings} in {@code knowledge_libraries.source_settings}
 * (ADR-0027, Entscheidung 1; ADR-0038): one JSON object with {@code region}, {@code pathStyle},
 * {@code scopes} (objects with {@code bucket} and {@code prefix}), {@code includePatterns} and
 * {@code excludePatterns} - the same shape the API carries, validated by the record on the way back
 * in.
 */
public final class S3SourceSettingsJson {

  private S3SourceSettingsJson() {}

  /**
   * The settings stored on {@code library}, {@code null} when it carries none.
   *
   * @throws S3SourceSettings.InvalidS3SourceSettingsException when the stored document does not
   *     read back into a valid configuration
   */
  public static S3SourceSettings of(KnowledgeLibrary library) {
    return read(library.getSourceSettings());
  }

  public static String write(S3SourceSettings settings) {
    return settings == null ? null : toData(settings).toJson();
  }

  public static ConnectorData toData(S3SourceSettings settings) {
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
    return ConnectorData.of(json);
  }

  /**
   * {@code null} for a {@code null} or blank column.
   *
   * @throws S3SourceSettings.InvalidS3SourceSettingsException when the stored document does not
   *     read back into a valid configuration
   */
  public static S3SourceSettings read(String json) {
    ConnectorData data;
    try {
      data = ConnectorData.fromJson(json);
    } catch (IllegalArgumentException e) {
      throw new S3SourceSettings.InvalidS3SourceSettingsException(
          "Die gespeicherte S3-Konfiguration ist kein gültiges JSON-Objekt.");
    }
    return data == null ? null : fromData(data);
  }

  /**
   * The record {@code data} describes.
   *
   * @throws S3Scope.InvalidS3ScopeException for a scope that cannot be one
   * @throws S3SourceSettings.InvalidS3SourceSettingsException for any other invalid value
   */
  public static S3SourceSettings fromData(ConnectorData data) {
    List<S3Scope> scopes = new ArrayList<>();
    if (data.get("scopes") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          scopes.add(S3Scope.of(string(map.get("bucket")), string(map.get("prefix"))));
        }
      }
    }
    return new S3SourceSettings(
        string(data.get("region")),
        Boolean.TRUE.equals(data.get("pathStyle")),
        scopes,
        strings(data.get("includePatterns")),
        strings(data.get("excludePatterns")));
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static List<String> strings(Object value) {
    if (value == null) {
      return null;
    }
    List<String> result = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        result.add(item == null ? null : item.toString());
      }
    }
    return result;
  }
}
