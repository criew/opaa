package io.opaa.indexing.source.dropbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The team behind a team app's token: the admin who consented ({@code
 * team/token/get_authenticated_admin}) and the team folders among the namespaces {@code
 * team/namespaces/list} names. Only a namespace of type {@code team_folder} is offered or read; a
 * member's folder, a shared folder or an app folder never is, whatever the token would reach.
 */
final class DropboxTeam {

  /** The finding when the token is a member's: the team routes refuse it. */
  static final String NOT_A_TEAM_CONSENT =
      "Dropbox nimmt die Zustimmung nicht als Team-App an; die Quelle muss von einer"
          + " Team-Administration neu verbunden werden.";

  static final String TEAM_FOLDER = "team_folder";

  private static final int PAGE_SIZE = 1000;

  /** One namespace of type {@code team_folder}: its id and name. */
  record Namespace(String id, String name) {}

  /** The team folders read so far; {@code complete} when the list was read to its end. */
  record TeamFolders(List<Namespace> folders, boolean complete) {}

  private DropboxTeam() {}

  /**
   * The profile of the admin who consented, {@code admin_profile} of the answer.
   *
   * @throws DropboxApiException as Dropbox refuses, {@link #NOT_A_TEAM_CONSENT} for a member's
   *     token
   */
  static JsonNode admin(DropboxApi api) throws DropboxApiException, InterruptedException {
    try {
      JsonNode profile = api.rpc("team/token/get_authenticated_admin", null).get("admin_profile");
      if (profile == null || DropboxFileStore.text(profile, "team_member_id") == null) {
        throw new DropboxApiException(
            DropboxApiException.Kind.TRANSIENT,
            200,
            null,
            "Dropbox hat die Team-Administration der Zustimmung nicht genannt.");
      }
      return profile;
    } catch (DropboxApiException e) {
      throw teamFailure(e);
    }
  }

  /**
   * The team folders, page by page until {@code wanted} - every one when empty - are found or
   * {@code maxPages} are read.
   *
   * @throws DropboxApiException as Dropbox refuses, {@link #NOT_A_TEAM_CONSENT} for a member's
   *     token
   */
  static TeamFolders teamFolders(DropboxApi api, Set<String> wanted, int maxPages)
      throws DropboxApiException, InterruptedException {
    List<Namespace> folders = new ArrayList<>();
    try {
      JsonNode answer = api.rpc("team/namespaces/list", Map.of("limit", PAGE_SIZE));
      for (int page = 1; ; page++) {
        JsonNode items = answer.get("namespaces");
        if (items != null && items.isArray()) {
          for (JsonNode item : items) {
            JsonNode type = item.get("namespace_type");
            String id = DropboxFileStore.text(item, "namespace_id");
            if (id != null
                && type != null
                && TEAM_FOLDER.equals(DropboxFileStore.text(type, ".tag"))) {
              folders.add(new Namespace(id, DropboxFileStore.text(item, "name")));
            }
          }
        }
        JsonNode more = answer.get("has_more");
        if (more == null || !more.asBoolean()) {
          return new TeamFolders(folders, true);
        }
        if (!wanted.isEmpty() && folders.stream().map(Namespace::id).toList().containsAll(wanted)) {
          return new TeamFolders(folders, true);
        }
        if (page >= maxPages) {
          return new TeamFolders(folders, false);
        }
        String cursor = DropboxFileStore.text(answer, "cursor");
        if (cursor == null || cursor.isBlank()) {
          throw new DropboxApiException(
              DropboxApiException.Kind.TRANSIENT,
              200,
              null,
              "Dropbox hat die Liste der Team-Ordner ohne Fortsetzungspunkt abgebrochen.");
        }
        answer = api.rpc("team/namespaces/list/continue", Map.of("cursor", cursor));
      }
    } catch (DropboxApiException e) {
      throw teamFailure(e);
    }
  }

  /**
   * {@code e} as a team route's failure: only Dropbox's plain-text refusal of a token of the other
   * kind means a member's token; any other refused request stays as it is.
   */
  private static DropboxApiException teamFailure(DropboxApiException e) {
    return e.kind() == DropboxApiException.Kind.BAD_REQUEST && e.is(DropboxApi.TOKEN_KIND_MISMATCH)
        ? new DropboxApiException(
            DropboxApiException.Kind.BAD_REQUEST, e.status(), e.summary(), NOT_A_TEAM_CONSENT)
        : e;
  }
}
