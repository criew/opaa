package io.opaa.indexing.source.sharepoint;

import io.opaa.common.ValidationException;
import io.opaa.indexing.filesync.FileContainer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One document library of a SharePoint library configuration: the drive, optionally narrowed to
 * folders with their subfolders. The container key is {@code drive:<driveId>}; the folders are a
 * filter within it, not containers of their own. {@code name} and {@code folderNames} are only
 * shown, never compared.
 */
record SharePointLibrary(
    String driveId, List<String> folders, Map<String, String> folderNames, String name) {

  static final String KEY_PREFIX = "drive:";
  static final int MAX_FOLDERS = 50;
  static final int MAX_NAME_LENGTH = 200;

  /**
   * Graph drive and item ids: letters, digits, {@code !}, {@code -} and {@code _} - nothing that
   * could change a request path or a container key.
   */
  static final Pattern ID = Pattern.compile("[A-Za-z0-9!_-]{1,200}");

  SharePointLibrary {
    folders = List.copyOf(folders);
    folderNames = Map.copyOf(folderNames);
  }

  /**
   * Reads {@code {"driveId": id, "folders": [id | {"id": id, "name": text}, ...], "name": text}};
   * only the drive is required. A folder listed twice keeps its first place and the last name any
   * of its entries gives; an entry without a name leaves an earlier name standing.
   */
  static SharePointLibrary fromJson(Object value) {
    if (!(value instanceof Map<?, ?> map) || !(map.get("driveId") instanceof String driveId)) {
      throw invalid();
    }
    for (Object key : map.keySet()) {
      if (!Set.of("driveId", "folders", "name").contains(key)) {
        throw invalid();
      }
    }
    requireId(driveId);
    String name = shownName(map.get("name"));
    Object rawFolders = map.get("folders");
    List<String> folders = new ArrayList<>();
    Map<String, String> folderNames = new LinkedHashMap<>();
    if (rawFolders != null) {
      if (!(rawFolders instanceof List<?> list)) {
        throw invalid();
      }
      if (list.size() > MAX_FOLDERS) {
        throw new ValidationException(
            "sourceSettings.libraries: höchstens " + MAX_FOLDERS + " Ordner je Bibliothek");
      }
      for (Object folder : list) {
        String id;
        String shown = null;
        if (folder instanceof String plain) {
          id = requireId(plain);
        } else if (folder instanceof Map<?, ?> named
            && named.get("id") instanceof String namedId
            && Set.of("id", "name").containsAll(named.keySet())) {
          id = requireId(namedId);
          shown = shownName(named.get("name"));
        } else {
          throw invalid();
        }
        if (!folders.contains(id)) {
          folders.add(id);
        }
        if (shown != null) {
          folderNames.put(id, shown);
        }
      }
    }
    return new SharePointLibrary(driveId, folders, folderNames, name);
  }

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("driveId", driveId);
    if (!folders.isEmpty()) {
      List<Object> entries = new ArrayList<>();
      for (String id : folders) {
        entries.add(folderNames.containsKey(id) ? namedFolder(id, folderNames.get(id)) : id);
      }
      json.put("folders", entries);
    }
    if (name != null) {
      json.put("name", name);
    }
    return json;
  }

  String key() {
    return KEY_PREFIX + driveId;
  }

  FileContainer container() {
    return new FileContainer(key());
  }

  /** What the library covers, comparable across saves: the drive and its sorted folder filter. */
  String coverage() {
    return key() + folders.stream().sorted().toList();
  }

  static String requireId(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new ValidationException(
          "sourceSettings.libraries: Die ID einer Bibliothek oder eines Ordners besteht aus"
              + " Buchstaben, Ziffern, „!“, „-“ und „_“");
    }
    return id;
  }

  private static Map<String, Object> namedFolder(String id, String shown) {
    Map<String, Object> folder = new LinkedHashMap<>();
    folder.put("id", id);
    folder.put("name", shown);
    return folder;
  }

  /** A shown name: a text up to {@value #MAX_NAME_LENGTH} characters; blank counts as none. */
  private static String shownName(Object raw) {
    if (raw != null && (!(raw instanceof String text) || text.length() > MAX_NAME_LENGTH)) {
      throw new ValidationException(
          "sourceSettings.libraries: Der Name einer Bibliothek oder eines Ordners ist ein Text bis "
              + MAX_NAME_LENGTH
              + " Zeichen");
    }
    return raw == null || ((String) raw).isBlank() ? null : (String) raw;
  }

  private static ValidationException invalid() {
    return new ValidationException(
        "sourceSettings.libraries: Jede Bibliothek ist {\"driveId\": \"<ID>\"}, optional mit"
            + " \"folders\": [\"<ID>\" oder {\"id\": \"<ID>\", \"name\": \"…\"}, …] und \"name\"");
  }
}
