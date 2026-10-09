package io.opaa.indexing.source.sharepoint;

import tools.jackson.databind.JsonNode;

/**
 * The fields of a Graph {@code driveItem} the connector reads. A {@code delta} entry carries no
 * path; the folder chain follows the parents by id. Facets decide the kind: {@code deleted} for a
 * removal, {@code root}, {@code folder}, {@code package} (a OneNote notebook) or {@code file}.
 *
 * @param size in bytes, {@code -1} when the answer names none
 */
record DriveItem(
    String id,
    String name,
    String parentId,
    boolean deleted,
    boolean root,
    boolean folder,
    String packageType,
    boolean malware,
    long size,
    String quickXorHash,
    String cTag,
    String lastModified,
    String mimeType) {

  /** The properties a listing or a single read asks for ({@code $select}). */
  static final String SELECT =
      "id,name,parentReference,file,folder,package,root,deleted,malware,size,cTag,"
          + "lastModifiedDateTime";

  static DriveItem of(JsonNode node) {
    JsonNode parent = node.get("parentReference");
    JsonNode file = node.get("file");
    JsonNode hashes = file == null ? null : file.get("hashes");
    JsonNode pack = node.get("package");
    return new DriveItem(
        text(node, "id"),
        text(node, "name"),
        text(parent, "id"),
        present(node, "deleted"),
        present(node, "root"),
        present(node, "folder"),
        pack == null || pack.isNull() ? null : orEmpty(text(pack, "type")),
        present(node, "malware"),
        node.get("size") != null && node.get("size").isNumber() ? node.get("size").asLong() : -1,
        text(hashes, "quickXorHash"),
        text(node, "cTag"),
        text(node, "lastModifiedDateTime"),
        text(file, "mimeType"));
  }

  /** Whether the item is a folder or the library's root, never itself a document. */
  boolean holdsItems() {
    return root || folder;
  }

  private static boolean present(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && !value.isNull();
  }

  private static String orEmpty(String text) {
    return text == null ? "" : text;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() || !value.isValueNode() ? null : value.asString();
  }
}
