package io.opaa.indexing.source.sharepoint;

import io.opaa.common.ValidationException;
import io.opaa.indexing.filesync.FileContainer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One document library of a SharePoint library configuration: the drive, optionally narrowed to
 * folders with their subfolders. The container key is {@code drive:<driveId>}; the folders are a
 * filter within it, not containers of their own. {@code name} is only shown, never compared.
 */
record SharePointLibrary(String driveId, List<String> folders, String name) {

  static final String KEY_PREFIX = "drive:";
  static final int MAX_FOLDERS = 50;

  /**
   * Graph drive and item ids: letters, digits, {@code !}, {@code -} and {@code _} - nothing that
   * could change a request path or a container key.
   */
  static final Pattern ID = Pattern.compile("[A-Za-z0-9!_-]{1,200}");

  SharePointLibrary {
    folders = List.copyOf(folders);
  }

  /**
   * Reads {@code {"driveId": id, "folders": [id, ...], "name": text}}; only the drive is required.
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
    Object rawName = map.get("name");
    if (rawName != null && (!(rawName instanceof String text) || text.length() > 200)) {
      throw new ValidationException(
          "sourceSettings.libraries: Der Name einer Bibliothek ist ein Text bis 200 Zeichen");
    }
    String name = rawName == null || ((String) rawName).isBlank() ? null : (String) rawName;
    Object rawFolders = map.get("folders");
    Set<String> folders = new LinkedHashSet<>();
    if (rawFolders != null) {
      if (!(rawFolders instanceof List<?> list)) {
        throw invalid();
      }
      if (list.size() > MAX_FOLDERS) {
        throw new ValidationException(
            "sourceSettings.libraries: höchstens " + MAX_FOLDERS + " Ordner je Bibliothek");
      }
      for (Object folder : list) {
        if (!(folder instanceof String id)) {
          throw invalid();
        }
        folders.add(requireId(id));
      }
    }
    return new SharePointLibrary(driveId, new ArrayList<>(folders), name);
  }

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("driveId", driveId);
    if (!folders.isEmpty()) {
      json.put("folders", folders);
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

  private static ValidationException invalid() {
    return new ValidationException(
        "sourceSettings.libraries: Jede Bibliothek ist {\"driveId\": \"<ID>\"}, optional mit"
            + " \"folders\": [\"<ID>\", …] und \"name\"");
  }
}
