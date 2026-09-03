package io.opaa.indexing.source.confluence;

import io.opaa.api.types.ConfluenceEdition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Confluence Data Center adapter: the v1 {@code /rest/api} with {@code start}/{@code limit}
 * pagination - followed through the instance's own {@code _links.next}, which Data Center gives
 * relative to {@code _links.base} (the instance address including its context path) - and {@code
 * expand} for nested data ({@code version}, {@code ancestors}, {@code body.storage}, {@code
 * space}). Authentication is {@code Bearer <PAT>} ({@link
 * ConfluenceCredentials.DataCenterPersonalAccessToken}).
 */
final class DataCenterConfluenceClient extends AbstractConfluenceClient {

  static final String REST = "/rest/api";

  DataCenterConfluenceClient(ConfluenceHttp http) {
    super(http);
  }

  @Override
  public ConfluenceEdition edition() {
    return ConfluenceEdition.DATA_CENTER;
  }

  @Override
  public void verifyCredentials() throws ConfluenceAccessException, InterruptedException {
    // Data Center does not refuse an unknown or revoked token: it serves the request anonymously
    // with HTTP 200 (seen against the real instance, #1171) - a space listing would simply be
    // empty. Only the current-user resource tells who the token actually is.
    String resource = "das angemeldete Benutzerkonto";
    ConfluenceHttp.Response response = http.get(base() + REST + "/user/current", resource);
    if (response.status() == 404) {
      // A 404 on a resource with a subject says "no current user" first - only the API root
      // decides whether there is a Data Center here at all (the same signature the detector uses).
      ConfluenceHttp.Response api = http.get(base() + REST + "/space?limit=1", "die Space-Liste");
      if (api.status() == 404) {
        throw new ConfluenceAccessException.EditionMismatch(
            "Unter dieser Adresse antwortet kein Confluence Data Center (die API /rest/api fehlt).");
      }
      throw new ConfluenceAccessException.Authentication(
          "Confluence Data Center hat das Personal Access Token nicht angenommen (HTTP 404 auf das"
              + " angemeldete Benutzerkonto): Das Token ist ungültig, abgelaufen oder widerrufen.");
    }
    if (response.status() != 200) {
      throw http.failure(response.status(), resource);
    }
    JsonNode user = http.parse(response, resource);
    if ("anonymous".equalsIgnoreCase(user.path("type").asString(""))
        || "anonymous".equalsIgnoreCase(user.path("username").asString(""))) {
      throw new ConfluenceAccessException.Authentication(
          "Confluence Data Center hat das Personal Access Token nicht angenommen und behandelt die"
              + " Anfrage anonym (HTTP 200, anonymer Benutzer): Das Token ist ungültig, abgelaufen"
              + " oder widerrufen.");
    }
  }

  @Override
  public List<ConfluenceSpace> listSpaces() throws ConfluenceAccessException, InterruptedException {
    String resource = "die Space-Liste";
    List<ConfluenceSpace> spaces = new ArrayList<>();
    for (JsonNode node :
        listAll(
            base() + REST + "/space?limit=" + pageSize() + "&start=0",
            resource,
            reader(resource))) {
      spaces.add(new ConfluenceSpace(text(node, "id"), text(node, "key"), text(node, "name")));
    }
    return spaces;
  }

  @Override
  public List<ConfluencePageSummary> listPages(String spaceKey)
      throws ConfluenceAccessException, InterruptedException {
    String resource = "die Seiten des Space " + spaceKey;
    List<ConfluencePageSummary> pages = new ArrayList<>();
    String first =
        base()
            + REST
            + "/content?spaceKey="
            + encode(spaceKey)
            + "&type=page&status=current&expand=version,ancestors&limit="
            + pageSize()
            + "&start=0";
    for (JsonNode node : listAll(first, resource, reader(resource))) {
      pages.add(
          new ConfluencePageSummary(
              text(node, "id"),
              spaceKey,
              text(node, "title"),
              intOr(node.path("version").path("number"), -1),
              directParentId(node.path("ancestors"))));
    }
    return pages;
  }

  @Override
  public Optional<ConfluencePage> fetchPage(String pageId)
      throws ConfluenceAccessException, InterruptedException {
    String resource = "die Seite " + pageId;
    String expand = "expand=body.storage,version,ancestors,space";
    String url = base() + REST + "/content/" + segment(pageId) + "?" + expand;
    Optional<JsonNode> found = http.getJsonOrNotFound(url, resource);
    if (found.isEmpty()) {
      // Data Center hides a trashed page behind 404 unless asked for that status explicitly - the
      // second look turns "gone or unreadable" into the positive finding "in the trash" where it
      // is.
      found = http.getJsonOrNotFound(url + "&status=trashed", resource);
      if (found.isEmpty()) {
        return Optional.empty();
      }
    }
    JsonNode node = found.get();
    String spaceKey = text(node.path("space"), "key");
    List<String> ancestors = new ArrayList<>();
    JsonNode ancestorNodes = node.path("ancestors");
    if (ancestorNodes.isArray()) {
      for (JsonNode ancestor : ancestorNodes) {
        String title = text(ancestor, "title");
        if (title != null) {
          ancestors.add(title);
        }
      }
    }
    return Optional.of(
        new ConfluencePage(
            text(node, "id"),
            spaceKey,
            text(node, "title"),
            intOr(node.path("version").path("number"), -1),
            ConfluencePageStatus.fromApi(text(node, "status")),
            ancestors,
            text(node.path("body").path("storage"), "value"),
            pageUrl(spaceKey, pageId),
            instantOrNull(text(node.path("version"), "when"))));
  }

  @Override
  public List<ConfluenceAttachment> listAttachments(String pageId)
      throws ConfluenceAccessException, InterruptedException {
    String resource = "die Anhänge der Seite " + pageId;
    List<ConfluenceAttachment> attachments = new ArrayList<>();
    String first =
        base()
            + REST
            + "/content/"
            + segment(pageId)
            + "/child/attachment?expand=version&limit="
            + pageSize()
            + "&start=0";
    for (JsonNode node : listAll(first, resource, reader(resource))) {
      String download = text(node.path("_links"), "download");
      attachments.add(
          ConfluenceAttachment.of(
              text(node, "id"),
              pageId,
              text(node, "title"),
              text(node.path("extensions"), "mediaType"),
              longOr(node.path("extensions").path("fileSize"), -1L),
              intOr(node.path("version").path("number"), -1),
              download == null ? null : http.resolveLink(download, resource)));
    }
    return attachments;
  }

  @Override
  public List<String> searchPageIdsModifiedSince(Set<String> spaceKeys, Instant since)
      throws ConfluenceAccessException, InterruptedException {
    String resource = "die Änderungssuche";
    List<String> ids = new ArrayList<>();
    String first =
        base()
            + REST
            + "/content/search?cql="
            + encode(changedPagesCql(spaceKeys, since))
            + "&limit="
            + pageSize()
            + "&start=0";
    for (JsonNode node : listAll(first, resource, reader(resource))) {
      String id = text(node, "id");
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  @Override
  public String pageUrl(String spaceKey, String pageId) {
    return base() + "/pages/viewpage.action?pageId=" + encode(pageId);
  }

  /** Data Center lists ancestors root-first; the direct parent is the last entry. */
  private static String directParentId(JsonNode ancestors) {
    if (!ancestors.isArray() || ancestors.isEmpty()) {
      return null;
    }
    return text(ancestors.get(ancestors.size() - 1), "id");
  }

  /**
   * Reads a v1 page: {@code results} plus {@code _links.next}, which Data Center gives relative to
   * the instance address (including the context path) - resolved and origin-checked.
   */
  private PageReader reader(String resource) {
    return page -> {
      String next = nextLink(page);
      return new Listing(results(page), next == null ? null : http.resolveLink(next, resource));
    };
  }
}
