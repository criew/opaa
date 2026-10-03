package io.opaa.indexing.source.googledrive;

import io.opaa.common.ValidationException;
import io.opaa.indexing.filesync.FileContainer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One area of a Google Drive library (ADR-0040, Entscheidung 5): a whole shared drive, a folder
 * with its subfolders, or the imitated account's own "Meine Ablage". The container key is {@code
 * drive:<id>}, {@code folder:<id>} or {@code myDrive}; {@code name} is only shown, never compared.
 */
record GoogleDriveScope(Kind kind, String id, String name) {

  enum Kind {
    DRIVE,
    FOLDER,
    MY_DRIVE
  }

  /** Drive ids are URL-safe base64-like strings; a stray character could break a query. */
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");

  static final String MY_DRIVE_KEY = "myDrive";

  static GoogleDriveScope drive(String id) {
    return new GoogleDriveScope(Kind.DRIVE, requireId(id), null);
  }

  static GoogleDriveScope folder(String id) {
    return new GoogleDriveScope(Kind.FOLDER, requireId(id), null);
  }

  static GoogleDriveScope myDrive() {
    return new GoogleDriveScope(Kind.MY_DRIVE, "root", null);
  }

  /**
   * Reads {@code {"drive": id}}, {@code {"folder": id}} or {@code {"myDrive": true}}, each with an
   * optional display {@code name}.
   */
  static GoogleDriveScope fromJson(Object value) {
    if (!(value instanceof Map<?, ?> map) || map.isEmpty() || map.size() > 2) {
      throw invalid();
    }
    Object rawName = map.get("name");
    if (map.size() == 2 && !map.containsKey("name")) {
      throw invalid();
    }
    if (rawName != null && (!(rawName instanceof String) || ((String) rawName).length() > 200)) {
      throw new ValidationException(
          "sourceSettings.scopes: Der Name eines Bereichs ist ein Text bis 200 Zeichen");
    }
    String name = rawName == null || ((String) rawName).isBlank() ? null : (String) rawName;
    if (map.get("drive") instanceof String id) {
      return new GoogleDriveScope(Kind.DRIVE, requireId(id), name);
    }
    if (map.get("folder") instanceof String id) {
      return new GoogleDriveScope(Kind.FOLDER, requireId(id), name);
    }
    if (Boolean.TRUE.equals(map.get("myDrive"))) {
      return myDrive();
    }
    throw invalid();
  }

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    switch (kind) {
      case DRIVE -> json.put("drive", id);
      case FOLDER -> json.put("folder", id);
      case MY_DRIVE -> json.put("myDrive", true);
    }
    if (name != null) {
      json.put("name", name);
    }
    return json;
  }

  String key() {
    return switch (kind) {
      case DRIVE -> "drive:" + id;
      case FOLDER -> "folder:" + id;
      case MY_DRIVE -> MY_DRIVE_KEY;
    };
  }

  FileContainer container() {
    return new FileContainer(key());
  }

  private static String requireId(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new ValidationException(
          "sourceSettings.scopes: Die ID einer Ablage oder eines Ordners besteht aus Buchstaben,"
              + " Ziffern, „-“ und „_“");
    }
    return id;
  }

  private static ValidationException invalid() {
    return new ValidationException(
        "sourceSettings.scopes: Jeder Bereich ist {\"drive\": \"<ID>\"}, {\"folder\": \"<ID>\"}"
            + " oder {\"myDrive\": true}");
  }
}
