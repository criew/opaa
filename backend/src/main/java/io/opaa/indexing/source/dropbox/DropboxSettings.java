package io.opaa.indexing.source.dropbox;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.SourceFolderPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The connector settings of a Dropbox library in {@code source_settings}: one to fifty {@code
 * folders} of the connected account, each read with everything below it ({@code /} is the whole
 * Dropbox), whether they lie in the team space instead of the member's own folder ({@code
 * teamSpace}), and the optional own full-sync rhythm {@code fullSyncIntervalDays}. No secret.
 *
 * @param folders normalised absolute paths, none lying in another (compared without case, as
 *     Dropbox compares them)
 */
record DropboxSettings(List<String> folders, boolean teamSpace, Integer fullSyncIntervalDays) {

  static final String FOLDERS = "folders";
  static final String TEAM_SPACE = "teamSpace";
  static final String FULL_SYNC_INTERVAL_DAYS = "fullSyncIntervalDays";
  static final Set<String> KEYS = Set.of(FOLDERS, TEAM_SPACE, FULL_SYNC_INTERVAL_DAYS);
  static final int MAX_FOLDERS = 50;
  static final int MAX_FULL_SYNC_INTERVAL_DAYS = 365;

  DropboxSettings {
    folders = List.copyOf(folders);
  }

  /**
   * The settings {@code data} describes, validated.
   *
   * @throws ValidationException with a German message naming the defect
   */
  static DropboxSettings read(ConnectorData data) {
    if (data == null) {
      throw new ValidationException(
          "sourceSettings.folders: mindestens ein Ordner ist erforderlich, wenn sourceType DROPBOX"
              + " ist");
    }
    data.requireOnly(KEYS);
    if (!(data.get(FOLDERS) instanceof List<?> raw) || raw.isEmpty()) {
      throw new ValidationException(
          "sourceSettings.folders: mindestens ein Ordner ist erforderlich, wenn sourceType DROPBOX"
              + " ist");
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
    Object team = data.get(TEAM_SPACE);
    if (team != null && !(team instanceof Boolean)) {
      throw new ValidationException("sourceSettings.teamSpace: ja oder nein (true/false)");
    }
    return new DropboxSettings(
        folders, Boolean.TRUE.equals(team), interval(data.get(FULL_SYNC_INTERVAL_DAYS)));
  }

  /** The stored settings, {@code null} for none. */
  static DropboxSettings stored(ConnectorData data) {
    return data == null ? null : read(data);
  }

  /** Whether {@code data} asks for the team space; {@code false} for none or an odd value. */
  static boolean teamSpaceOf(ConnectorData data) {
    return data != null && Boolean.TRUE.equals(data.get(TEAM_SPACE));
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put(FOLDERS, folders);
    if (teamSpace) {
      json.put(TEAM_SPACE, true);
    }
    if (fullSyncIntervalDays != null) {
      json.put(FULL_SYNC_INTERVAL_DAYS, fullSyncIntervalDays);
    }
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

  /** Whether {@code inner} is {@code outer} or lies below it, without regard to case. */
  static boolean contains(String outer, String inner) {
    String o = lower(outer);
    String i = lower(inner);
    return o.equals("/") || i.equals(o) || i.startsWith(o + "/");
  }

  /** The path as the API addresses it: {@code ""} for the root, else the folder itself. */
  static String apiPath(String folder) {
    return folder.equals("/") ? "" : folder;
  }

  static String lower(String path) {
    return path.toLowerCase(Locale.ROOT);
  }

  /** {@code 0} returns to the instance's rhythm, like an absent value. */
  private static Integer interval(Object value) {
    if (value == null || (value instanceof Number zero && zero.doubleValue() == 0)) {
      return null;
    }
    if (!(value instanceof Number number)
        || number.doubleValue() != number.intValue()
        || number.intValue() < 1
        || number.intValue() > MAX_FULL_SYNC_INTERVAL_DAYS) {
      throw new ValidationException(
          "sourceSettings.fullSyncIntervalDays: eine ganze Zahl von 1 bis "
              + MAX_FULL_SYNC_INTERVAL_DAYS);
    }
    return number.intValue();
  }
}
