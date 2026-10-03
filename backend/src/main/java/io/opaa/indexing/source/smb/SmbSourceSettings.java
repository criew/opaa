package io.opaa.indexing.source.smb;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceFolderPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The connector settings of an SMB library: the folders of the share that are read, each with
 * everything below it. Stored as {@code {"folders": ["/Projekte", ...]}}; {@code /} is the whole
 * share and the default when none is named.
 *
 * @param folders normalised absolute paths below the share, none lying in another
 */
record SmbSourceSettings(List<String> folders) {

  static final String FOLDERS = "folders";
  static final Set<String> KEYS = Set.of(FOLDERS);
  static final int MAX_FOLDERS = 50;
  private static final String FORBIDDEN_CHARACTERS = "\\:*?\"<>|";

  SmbSourceSettings {
    folders = List.copyOf(folders);
  }

  static final SmbSourceSettings WHOLE_SHARE = new SmbSourceSettings(List.of("/"));

  /**
   * The settings {@code data} describes, validated; absent settings or folders mean the whole
   * share.
   *
   * @throws ValidationException with a German message naming the defect
   */
  static SmbSourceSettings read(ConnectorData data) {
    if (data == null) {
      return WHOLE_SHARE;
    }
    data.requireOnly(KEYS);
    Object value = data.get(FOLDERS);
    if (value == null) {
      return WHOLE_SHARE;
    }
    if (!(value instanceof List<?> raw)) {
      throw new ValidationException("sourceSettings.folders: eine Liste von Ordnerpfaden erwartet");
    }
    if (raw.isEmpty()) {
      return WHOLE_SHARE;
    }
    if (raw.size() > MAX_FOLDERS) {
      throw new ValidationException(
          "sourceSettings.folders: höchstens " + MAX_FOLDERS + " Ordner je Bibliothek");
    }
    List<String> folders = new ArrayList<>();
    for (Object item : raw) {
      if (!(item instanceof String text)) {
        throw new ValidationException("sourceSettings.folders: jeder Eintrag ist ein Ordnerpfad");
      }
      String folder = normalize(text);
      for (String other : folders) {
        if (contains(other, folder) || contains(folder, other)) {
          throw new ValidationException(
              "sourceSettings.folders: „"
                  + folder
                  + "“ und „"
                  + other
                  + "“ überschneiden sich; jeder Ordner darf nur einmal gelesen werden");
        }
      }
      folders.add(folder);
    }
    return new SmbSourceSettings(folders);
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put(FOLDERS, folders);
    return ConnectorData.of(json);
  }

  /**
   * {@code /} or {@code /A/B}: one leading slash, no trailing one, a backslash read as a slash, no
   * empty segment, no {@code .} or {@code ..}, no character Windows forbids in a name, no comma or
   * line break - the path is the key the protocol names the folder by.
   */
  static String normalize(String path) {
    String trimmed = path == null ? "" : path.trim().replace('\\', '/');
    if (trimmed.isEmpty()) {
      throw new ValidationException("sourceSettings.folders: ein Ordnerpfad ist leer");
    }
    if (trimmed.indexOf(',') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
      throw new ValidationException(
          "sourceSettings.folders: „" + trimmed + "“ enthält ein Komma oder einen Zeilenumbruch");
    }
    List<String> segments = new ArrayList<>();
    for (String segment : trimmed.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      if (segment.equals(".")
          || segment.equals("..")
          || SourceFolderPath.rejects(segment)
          || segment.chars().anyMatch(c -> c < 0x20 || FORBIDDEN_CHARACTERS.indexOf(c) >= 0)) {
        throw new ValidationException(
            "sourceSettings.folders: „" + trimmed + "“ ist kein gültiger Ordnerpfad");
      }
      segments.add(segment);
    }
    return "/" + String.join("/", segments);
  }

  /** Whether {@code inner} is {@code outer} or lies below it; names compare case-insensitively. */
  static boolean contains(String outer, String inner) {
    String o = outer.toLowerCase(Locale.ROOT);
    String i = inner.toLowerCase(Locale.ROOT);
    return o.equals("/") || i.equals(o) || i.startsWith(o + "/");
  }

  /** The folder as a path below the share, {@code ""} for {@code /}. */
  static String sharePath(String folder) {
    return folder.equals("/") ? "" : folder.substring(1);
  }

  /** The folder's segments, empty for {@code /}. */
  static List<String> segments(String folder) {
    return folder.equals("/") ? List.of() : List.of(folder.substring(1).split("/"));
  }
}
