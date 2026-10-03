package io.opaa.indexing.source.filesystem;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connector settings of a FILESYSTEM library as {@code knowledge_libraries.source_settings}
 * carries them: an optional list of glob patterns, relative to {@code sourcePath}, whose matches
 * are not part of the source. Validated on construction with German, user-facing messages.
 */
public record FilesystemSourceSettings(List<String> excludePatterns) {

  public static final String EXCLUDE_PATTERNS = "excludePatterns";
  public static final Set<String> KEYS = Set.of(EXCLUDE_PATTERNS);
  public static final int MAX_PATTERNS = 50;
  public static final int MAX_PATTERN_LENGTH = 255;

  private static final String LABEL = "sourceSettings: Ausschlussmuster";

  public static final FilesystemSourceSettings NONE = new FilesystemSourceSettings(List.of());

  public FilesystemSourceSettings {
    excludePatterns = normalize(excludePatterns);
  }

  /** The settings {@code data} describes; {@code null} reads as {@link #NONE}. */
  public static FilesystemSourceSettings of(ConnectorData data) {
    if (data == null) {
      return NONE;
    }
    data.requireOnly(KEYS);
    Object raw = data.get(EXCLUDE_PATTERNS);
    if (raw == null) {
      return NONE;
    }
    if (!(raw instanceof List<?> list)) {
      throw new ValidationException(LABEL + " müssen als Liste angegeben werden.");
    }
    List<String> patterns = new ArrayList<>();
    for (Object item : list) {
      patterns.add(item == null ? null : item.toString());
    }
    return new FilesystemSourceSettings(patterns);
  }

  public ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put(EXCLUDE_PATTERNS, excludePatterns);
    return ConnectorData.of(json);
  }

  private static List<String> normalize(List<String> raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw.size() > MAX_PATTERNS) {
      throw new ValidationException(
          "sourceSettings: höchstens " + MAX_PATTERNS + " Ausschlussmuster je Bibliothek.");
    }
    List<String> patterns = new ArrayList<>();
    for (String candidate : raw) {
      String pattern = candidate == null ? "" : candidate.strip();
      if (pattern.isEmpty()) {
        throw new ValidationException(LABEL + ": ein leeres Muster ist unzulässig.");
      }
      if (pattern.length() > MAX_PATTERN_LENGTH) {
        throw new ValidationException(
            LABEL + ": ein Muster darf höchstens " + MAX_PATTERN_LENGTH + " Zeichen lang sein.");
      }
      if (pattern.startsWith("/")) {
        throw new ValidationException(
            LABEL
                + ": „"
                + pattern
                + "“ beginnt mit „/“. Muster gelten relativ zum Verzeichnispfad, z. B."
                + " „Archiv/**“.");
      }
      if (pattern.indexOf('\\') >= 0) {
        throw new ValidationException(
            LABEL + ": „" + pattern + "“ enthält „\\“. Ebenen werden mit „/“ getrennt.");
      }
      try {
        FilesystemGlob.compile(pattern);
      } catch (IllegalArgumentException e) {
        throw new ValidationException(
            LABEL
                + ": „"
                + pattern
                + "“ ist kein gültiges Glob-Muster, es "
                + e.getMessage()
                + ".");
      }
      if (patterns.contains(pattern)) {
        throw new ValidationException(
            LABEL + ": das Muster „" + pattern + "“ ist mehrfach angegeben.");
      }
      patterns.add(pattern);
    }
    return List.copyOf(patterns);
  }
}
