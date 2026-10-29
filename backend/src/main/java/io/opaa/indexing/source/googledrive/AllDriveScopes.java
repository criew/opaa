package io.opaa.indexing.source.googledrive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * "Alle Ablagen" as the account sees it now (ADR-0040, Entscheidung 5): every shared drive it is a
 * member of, listed to the last page, and with an imitated account its "Meine Ablage" first. A
 * listing that cannot be completed throws, so a run never works from a partial resolution.
 */
final class AllDriveScopes {

  static final String MY_DRIVE_NAME = "Meine Ablage";

  private AllDriveScopes() {}

  static List<GoogleDriveScope> resolve(DriveApi api, String subject)
      throws DriveApiException, InterruptedException {
    List<GoogleDriveScope> scopes = new ArrayList<>();
    if (subject != null) {
      scopes.add(GoogleDriveScope.myDrive());
    }
    String token = null;
    do {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("pageSize", "100");
      query.put("fields", "nextPageToken,drives(id,name)");
      if (token != null) {
        query.put("pageToken", token);
      }
      JsonNode answer = api.get("drives", query);
      JsonNode drives = answer.get("drives");
      if (drives != null && drives.isArray()) {
        for (JsonNode drive : drives) {
          JsonNode name = drive.get("name");
          scopes.add(
              new GoogleDriveScope(
                  GoogleDriveScope.Kind.DRIVE,
                  GoogleDriveScope.drive(drive.get("id").asString()).id(),
                  name == null || name.isNull() ? null : name.asString()));
        }
      }
      JsonNode next = answer.get("nextPageToken");
      token = next == null || next.isNull() || next.asString().isEmpty() ? null : next.asString();
    } while (token != null);
    return scopes;
  }

  /** The scope keys of {@code scopes} with the names a run protocol shows. */
  static Map<String, String> named(List<GoogleDriveScope> scopes) {
    Map<String, String> names = new LinkedHashMap<>();
    for (GoogleDriveScope scope : scopes) {
      names.put(
          scope.key(),
          scope.kind() == GoogleDriveScope.Kind.MY_DRIVE ? MY_DRIVE_NAME : scope.name());
    }
    return names;
  }
}
