package io.opaa.integration.confluence;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The handful of administrative calls the fixture needs to shape the instance - spaces, a page
 * hierarchy, restrictions, a second user, an attachment, the trash - over Confluence Data Center's
 * own REST and (where REST has no equivalent in 8.5: users, space permissions) its JSON-RPC
 * endpoint. Every call is checked; a failure names the call and the instance's answer.
 */
final class ConfluenceAdminApi {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceAdminApi.class);

  private final ConfluenceHttpSession session;
  private final String user;
  private final String password;

  ConfluenceAdminApi(ConfluenceHttpSession session, String user, String password) {
    this.session = session;
    this.user = user;
    this.password = password;
  }

  void createSpace(String key, String name) throws IOException, InterruptedException {
    ConfluenceHttpSession.Page page =
        session.rest(
            "POST",
            "/rest/api/space",
            "{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}",
            user,
            password);
    expect(page, "create space " + key, 200);
  }

  String createPage(String spaceKey, String title, String parentId, String storageBody)
      throws IOException, InterruptedException {
    String ancestors = parentId == null ? "" : ",\"ancestors\":[{\"id\":\"" + parentId + "\"}]";
    ConfluenceHttpSession.Page page =
        session.rest(
            "POST",
            "/rest/api/content",
            "{\"type\":\"page\",\"title\":\""
                + title
                + "\",\"space\":{\"key\":\""
                + spaceKey
                + "\"}"
                + ancestors
                + ",\"body\":{\"storage\":{\"value\":"
                + ConfluenceHttpSession.JSON.writeValueAsString(storageBody)
                + ",\"representation\":\"storage\"}}}",
            user,
            password);
    expect(page, "create page " + title, 200);
    return ConfluenceHttpSession.json(page).path("id").asString();
  }

  void uploadAttachment(String pageId, String fileName, String contentType, byte[] bytes)
      throws IOException, InterruptedException {
    ConfluenceHttpSession.Page page =
        session.upload(
            "/rest/api/content/" + pageId + "/child/attachment",
            fileName,
            contentType,
            bytes,
            user,
            password);
    expect(page, "upload " + fileName, 200);
  }

  /**
   * Read restriction to one user - every other account gets 404 on the page and never lists it.
   * Data Center 8.5 serves content restrictions only under {@code /rest/experimental}; the JSON-RPC
   * {@code setContentPermissions} is the stable path there.
   */
  void restrictReadToUser(String pageId, String userName) throws IOException, InterruptedException {
    String result =
        jsonRpc(
            "setContentPermissions",
            "[" + pageId + ",\"View\",[{\"type\":\"View\",\"userName\":\"" + userName + "\"}]]");
    log.info("setContentPermissions {} View {} -> {}", pageId, userName, result);
    if (!"true".equals(result)) {
      throw new IOException("restrict page " + pageId + " failed: " + result);
    }
  }

  /**
   * Moves the page to the trash ({@code status=trashed}); a permanent delete would be a second
   * call.
   */
  void trashPage(String pageId) throws IOException, InterruptedException {
    ConfluenceHttpSession.Page page =
        session.rest("DELETE", "/rest/api/content/" + pageId, null, user, password);
    expect(page, "trash page " + pageId, 204);
  }

  /**
   * Creates a local user. Data Center 8.5 has no REST endpoint for that; the JSON-RPC service
   * ({@code addUser}) still exists there and is the documented automation path.
   */
  void createUser(String userName, String password, String fullName, String email)
      throws IOException, InterruptedException {
    String result =
        jsonRpc(
            "addUser",
            "[{\"name\":\""
                + userName
                + "\",\"fullname\":\""
                + fullName
                + "\",\"email\":\""
                + email
                + "\"},\""
                + password
                + "\"]");
    log.info("addUser {} -> {}", userName, result);
    String added = jsonRpc("addUserToGroup", "[\"" + userName + "\",\"confluence-users\"]");
    if (!"true".equals(added)) {
      throw new IOException("addUserToGroup " + userName + " failed: " + added);
    }
  }

  /** Removes {@code VIEWSPACE} for a group so its members no longer see the space at all. */
  void removeGroupViewPermission(String spaceKey, String group)
      throws IOException, InterruptedException {
    String result =
        jsonRpc(
            "removePermissionFromSpace", "[\"VIEWSPACE\",\"" + group + "\",\"" + spaceKey + "\"]");
    log.info("removePermissionFromSpace {} {} -> {}", spaceKey, group, result);
    if (!"true".equals(result)) {
      throw new IOException(
          "removePermissionFromSpace " + spaceKey + " " + group + " failed: " + result);
    }
  }

  private String jsonRpc(String method, String paramsJson)
      throws IOException, InterruptedException {
    ConfluenceHttpSession.Page page =
        session.rest(
            "POST",
            "/rpc/json-rpc/confluenceservice-v2",
            "{\"jsonrpc\":\"2.0\",\"method\":\""
                + method
                + "\",\"params\":"
                + paramsJson
                + ",\"id\":1}",
            user,
            password);
    expect(page, "json-rpc " + method, 200);
    JsonNode json = ConfluenceHttpSession.json(page);
    if (json.has("error") && !json.path("error").isNull()) {
      throw new IOException("json-rpc " + method + " failed: " + json.path("error"));
    }
    return json.path("result").toString();
  }

  private static void expect(ConfluenceHttpSession.Page page, String what, int status)
      throws IOException {
    if (page.status() != status) {
      throw new IOException(
          what
              + " failed: HTTP "
              + page.status()
              + " "
              + page.body().substring(0, Math.min(400, page.body().length())));
    }
  }
}
