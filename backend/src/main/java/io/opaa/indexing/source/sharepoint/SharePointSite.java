package io.opaa.indexing.source.sharepoint;

import io.opaa.common.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A SharePoint site as the settings name it: {@code {"id": "<host>,<guid>,<guid>", "name": text}}.
 * The id is what the listing keys as {@code site:<id>}; the name is only shown, never compared. A
 * library entry carries its site so the selection dialog reopens in it; whether the library belongs
 * to the site, the connection test asks Graph.
 */
record SharePointSite(String id, String name) {

  /**
   * {@code host,siteCollectionId,webId} with two GUIDs; a host of letters, digits, dots and
   * hyphens, so nothing that could change a request path.
   */
  static final Pattern ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252},[0-9A-Fa-f-]{36},[0-9A-Fa-f-]{36}");

  /** Whether {@code id} has the form of a site id, without an empty host label. */
  static boolean isId(String id) {
    return id != null && ID.matcher(id).matches() && !id.contains("..");
  }

  /**
   * Reads {@code {"id": id, "name": text}}, the name optional and blank counting as none.
   *
   * @param field the settings field a refusal names
   */
  static SharePointSite fromJson(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)
        || !(map.get("id") instanceof String id)
        || !Set.of("id", "name").containsAll(map.keySet())
        || !isId(id)) {
      throw new ValidationException(
          field
              + ": Die Site einer Bibliothek ist {\"id\": \"<ID der Site aus der Auflistung>\"},"
              + " optional mit \"name\"");
    }
    Object raw = map.get("name");
    if (raw != null
        && (!(raw instanceof String text) || text.length() > SharePointLibrary.MAX_NAME_LENGTH)) {
      throw new ValidationException(
          field
              + ": Der Name einer Site ist ein Text bis "
              + SharePointLibrary.MAX_NAME_LENGTH
              + " Zeichen");
    }
    return new SharePointSite(id, raw == null || ((String) raw).isBlank() ? null : (String) raw);
  }

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", id);
    if (name != null) {
      json.put("name", name);
    }
    return json;
  }

  /** How a message names the site after „zur“: by its name, never by the long id. */
  String shown() {
    return name != null ? "Site „" + name + "“" : "gespeicherten Site";
  }
}
