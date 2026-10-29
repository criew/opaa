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
import java.util.regex.Pattern;

/**
 * The connector settings of a Dropbox library in {@code source_settings}: one to fifty {@code
 * folders} of the connected account, each read with everything below it ({@code /} is the whole
 * Dropbox), whether they lie in the team space instead of the member's own folder ({@code
 * teamSpace}), and the optional own full-sync rhythm {@code fullSyncIntervalDays}. Under a profile
 * marked {@code teamApp} - its default, never the library's own - the library reads one to fifty
 * {@code teamFolders} of the team instead, each a namespace id with the name it was chosen under.
 * No secret.
 *
 * @param folders normalised absolute paths, none lying in another (compared without case, as
 *     Dropbox compares them); empty for team folders
 * @param teamFolders the team folders by namespace; empty for folders
 * @param teamApp whether the settings came with the profile's mark of a team app; a request read on
 *     its own carries none
 */
record DropboxSettings(
    List<String> folders,
    boolean teamSpace,
    List<TeamFolder> teamFolders,
    boolean teamApp,
    Integer fullSyncIntervalDays) {

  static final String FOLDERS = "folders";
  static final String TEAM_SPACE = "teamSpace";
  static final String TEAM_FOLDERS = "teamFolders";
  static final String TEAM_APP = "teamApp";
  static final String FULL_SYNC_INTERVAL_DAYS = "fullSyncIntervalDays";
  static final Set<String> KEYS =
      Set.of(FOLDERS, TEAM_SPACE, TEAM_FOLDERS, TEAM_APP, FULL_SYNC_INTERVAL_DAYS);
  static final int MAX_FOLDERS = 50;
  static final int MAX_FULL_SYNC_INTERVAL_DAYS = 365;
  static final int MAX_NAME_LENGTH = 255;

  /** A namespace id as Dropbox issues it: decimal digits. */
  private static final Pattern NAMESPACE_ID = Pattern.compile("[0-9]{1,32}");

  /**
   * One team folder: its namespace id, the scope a run reads, and the name it was chosen under,
   * which only labels it ({@code null} for none).
   */
  record TeamFolder(String id, String name) {

    /** The name the folder is shown under, its id where it has none. */
    String label() {
      return name == null ? id : name;
    }
  }

  DropboxSettings {
    folders = List.copyOf(folders);
    teamFolders = List.copyOf(teamFolders);
  }

  /**
   * The settings {@code data} describes, validated as far as {@code data} alone tells: folders or
   * team folders, not both. Whether team folders fit the profile is {@link #requireFitsProfile}.
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
    Object app = data.get(TEAM_APP);
    if (app != null && !(app instanceof Boolean)) {
      throw new ValidationException("sourceSettings.teamApp: ja oder nein (true/false)");
    }
    boolean teamApp = Boolean.TRUE.equals(app);
    Object team = data.get(TEAM_SPACE);
    if (team != null && !(team instanceof Boolean)) {
      throw new ValidationException("sourceSettings.teamSpace: ja oder nein (true/false)");
    }
    Integer interval = interval(data.get(FULL_SYNC_INTERVAL_DAYS));
    if (teamApp || data.has(TEAM_FOLDERS)) {
      if (data.has(FOLDERS) || Boolean.TRUE.equals(team)) {
        throw new ValidationException(
            "sourceSettings.teamFolders: über eine Team-App liest die Bibliothek Team-Ordner,"
                + " keine Ordnerpfade und keinen Team-Bereich");
      }
      return new DropboxSettings(
          List.of(), false, teamFolders(data.get(TEAM_FOLDERS)), teamApp, interval);
    }
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
    return new DropboxSettings(folders, Boolean.TRUE.equals(team), List.of(), false, interval);
  }

  /**
   * {@link #read} of the effective settings, which carry the profile's mark: team folders only
   * through a team app.
   *
   * @throws ValidationException with a German message naming the defect
   */
  static DropboxSettings effective(ConnectorData data) {
    DropboxSettings settings = read(data);
    settings.requireFitsProfile();
    return settings;
  }

  /** The stored effective settings, {@code null} for none. */
  static DropboxSettings stored(ConnectorData data) {
    return data == null ? null : effective(data);
  }

  /**
   * Refuses team folders without the profile's mark of a team app: a member's consent reaches no
   * namespace of the team.
   */
  void requireFitsProfile() {
    if (!teamFolders.isEmpty() && !teamApp) {
      throw new ValidationException(
          "sourceSettings.teamFolders: Team-Ordner liest nur ein Zugang mit Team-App");
    }
  }

  /** Whether the library reads team folders of a team app rather than folder paths. */
  boolean readsTeamFolders() {
    return teamApp || !teamFolders.isEmpty();
  }

  /** The keys of the containers a run reads: the folder paths or the namespace ids. */
  List<String> containerKeys() {
    return readsTeamFolders() ? teamFolders.stream().map(TeamFolder::id).toList() : folders;
  }

  /** Whether {@code data} asks for the team space; {@code false} for none or an odd value. */
  static boolean teamSpaceOf(ConnectorData data) {
    return data != null && Boolean.TRUE.equals(data.get(TEAM_SPACE));
  }

  /** Whether {@code data} carries the profile's mark of a team app. */
  static boolean teamAppOf(ConnectorData data) {
    return data != null && Boolean.TRUE.equals(data.get(TEAM_APP));
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    if (readsTeamFolders()) {
      List<Map<String, Object>> entries = new ArrayList<>();
      for (TeamFolder folder : teamFolders) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", folder.id());
        if (folder.name() != null) {
          entry.put("name", folder.name());
        }
        entries.add(entry);
      }
      json.put(TEAM_FOLDERS, entries);
      if (teamApp) {
        json.put(TEAM_APP, true);
      }
    } else {
      json.put(FOLDERS, folders);
      if (teamSpace) {
        json.put(TEAM_SPACE, true);
      }
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

  private static List<TeamFolder> teamFolders(Object value) {
    if (!(value instanceof List<?> raw) || raw.isEmpty()) {
      throw new ValidationException(
          "sourceSettings.teamFolders: mindestens ein Team-Ordner ist erforderlich");
    }
    if (raw.size() > MAX_FOLDERS) {
      throw new ValidationException(
          "sourceSettings.teamFolders: höchstens " + MAX_FOLDERS + " Team-Ordner je Bibliothek");
    }
    List<TeamFolder> folders = new ArrayList<>();
    for (Object item : raw) {
      if (!(item instanceof Map<?, ?> entry)
          || !(entry.get("id") instanceof String id)
          || !NAMESPACE_ID.matcher(id.trim()).matches()
          || entry.keySet().stream().anyMatch(key -> !"id".equals(key) && !"name".equals(key))) {
        throw new ValidationException(
            "sourceSettings.teamFolders: jeder Eintrag nennt die Namespace-ID eines Team-Ordners"
                + " (id) und höchstens seinen Namen (name)");
      }
      String name = null;
      if (entry.get("name") != null) {
        if (!(entry.get("name") instanceof String text) || text.length() > MAX_NAME_LENGTH) {
          throw new ValidationException(
              "sourceSettings.teamFolders: der Name eines Team-Ordners ist ein Text bis "
                  + MAX_NAME_LENGTH
                  + " Zeichen");
        }
        name = text.isBlank() ? null : text.trim();
      }
      String key = id.trim();
      if (folders.stream().anyMatch(folder -> folder.id().equals(key))) {
        throw new ValidationException(
            "sourceSettings.teamFolders: der Team-Ordner " + key + " steht doppelt in der Liste");
      }
      folders.add(new TeamFolder(key, name));
    }
    return folders;
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
