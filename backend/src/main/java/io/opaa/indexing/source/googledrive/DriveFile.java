package io.opaa.indexing.source.googledrive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One file resource of the Drive API, the fields the connector asks for ({@link #FIELDS}).
 *
 * @param size in bytes, {@code -1} for a Google file, which has none
 * @param md5 the checksum of a binary file, {@code null} for a Google file
 */
record DriveFile(
    String id,
    String name,
    String mimeType,
    long size,
    String md5,
    Instant modifiedTime,
    List<String> parents,
    boolean trashed,
    String driveId,
    boolean canDownload) {

  static final String FIELDS =
      "id,name,mimeType,size,md5Checksum,modifiedTime,parents,trashed,driveId,"
          + "capabilities/canDownload";

  static final String FOLDER = "application/vnd.google-apps.folder";
  static final String SHORTCUT = "application/vnd.google-apps.shortcut";

  static DriveFile of(JsonNode node) {
    List<String> parents = new ArrayList<>();
    JsonNode parentNodes = node.get("parents");
    if (parentNodes != null && parentNodes.isArray()) {
      parentNodes.forEach(parent -> parents.add(parent.asString()));
    }
    JsonNode capabilities = node.get("capabilities");
    JsonNode canDownload = capabilities == null ? null : capabilities.get("canDownload");
    return new DriveFile(
        text(node, "id"),
        text(node, "name"),
        text(node, "mimeType"),
        node.get("size") == null ? -1 : Long.parseLong(node.get("size").asString()),
        text(node, "md5Checksum"),
        node.get("modifiedTime") == null ? null : Instant.parse(text(node, "modifiedTime")),
        List.copyOf(parents),
        node.get("trashed") != null && node.get("trashed").asBoolean(),
        text(node, "driveId"),
        canDownload == null || canDownload.asBoolean());
  }

  boolean isFolder() {
    return FOLDER.equals(mimeType);
  }

  String parent() {
    return parents.isEmpty() ? null : parents.get(0);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
