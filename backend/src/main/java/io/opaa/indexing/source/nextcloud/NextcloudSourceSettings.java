package io.opaa.indexing.source.nextcloud;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceFolderPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connector settings of a Nextcloud library: the folders of the technical user that are read,
 * each with everything below it - shares and group folders included, as the user sees them. Stored
 * as {@code {"folders": ["/Projekte", ...]}}; {@code /} is the user's whole tree.
 *
 * @param folders normalised absolute paths, none lying in another
 */
record NextcloudSourceSettings(List<String> folders) {

  static final String FOLDERS = "folders";
  static final Set<String> KEYS = Set.of(FOLDERS);
  static final int MAX_FOLDERS = 50;

  NextcloudSourceSettings {
    folders = List.copyOf(folders);
  }

  /**
   * The settings {@code data} describes, validated.
   *
   * @throws ValidationException with a German message naming the defect
   */
  static NextcloudSourceSettings read(ConnectorData data) {
    if (data == null) {
      throw new ValidationException(
          "sourceSettings.folders: mindestens ein Ordner ist erforderlich, wenn sourceType"
              + " NEXTCLOUD ist");
    }
    data.requireOnly(KEYS);
    if (!(data.get(FOLDERS) instanceof List<?> raw) || raw.isEmpty()) {
      throw new ValidationException(
          "sourceSettings.folders: mindestens ein Ordner ist erforderlich, wenn sourceType"
              + " NEXTCLOUD ist");
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
    return new NextcloudSourceSettings(folders);
  }

  /** The stored settings, {@code null} for none. */
  static NextcloudSourceSettings stored(ConnectorData data) {
    return data == null ? null : read(data);
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put(FOLDERS, folders);
    return ConnectorData.of(json);
  }

  /**
   * {@code /} or {@code /A/B}: one leading slash, no trailing one, no empty segment, no {@code .}
   * or {@code ..}, no comma or line break - the path is the key the protocol names the folder by.
   */
  static String normalize(String path) {
    String trimmed = path == null ? "" : path.trim();
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
      if (segment.equals(".") || segment.equals("..") || SourceFolderPath.rejects(segment)) {
        throw new ValidationException(
            "sourceSettings.folders: „" + trimmed + "“ ist kein gültiger Ordnerpfad");
      }
      segments.add(segment);
    }
    return "/" + String.join("/", segments);
  }

  /** Whether {@code inner} is {@code outer} or lies below it. */
  static boolean contains(String outer, String inner) {
    return outer.equals("/") || inner.equals(outer) || inner.startsWith(outer + "/");
  }

  /** The folder's segments, empty for {@code /}. */
  static List<String> segments(String folder) {
    return folder.equals("/") ? List.of() : List.of(folder.substring(1).split("/"));
  }
}
