package io.opaa.indexing.source.confluence;

import io.opaa.api.types.ConfluenceEdition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Confluence Cloud adapter: {@code /wiki/api/v2} for spaces, pages and attachments (cursor
 * pagination by following the instance's own {@code _links.next}, no {@code expand} - nested data
 * is fetched per relation), the still-supported v1 {@code /wiki/rest/api/search} for CQL (likewise
 * cursor-paginated through {@code _links.next}). Authentication is HTTP Basic from e-mail and API
 * token ({@link ConfluenceCredentials.CloudApiToken}).
 *
 * <p>Cloud hands out links in two shapes - from the host root ({@code /wiki/api/v2/...}) and
 * relative to the {@code /wiki} context ({@code /api/v2/...}, {@code /rest/api/search?...}, {@code
 * /download/attachments/...}); {@link #wikiLink} normalises both and, through {@link
 * ConfluenceHttp#resolveLink}, refuses any link that leaves the instance's origin.
 */
final class CloudConfluenceClient extends AbstractConfluenceClient {

  static final String WIKI = "/wiki";
  static final String API_V2 = WIKI + "/api/v2";
  static final String SEARCH_V1 = WIKI + "/rest/api/search";

  /** Cloud's own maximum for a v2 listing. */
  private static final int V2_MAX_LIMIT = 250;

  /** Bound on the ancestor chain walked in memory - deeper is a cycle, not a hierarchy. */
  private static final int MAX_ANCESTOR_DEPTH = 100;

  private final Map<String, ConfluenceSpace> spacesByKey = new HashMap<>();
  private final Map<String, ConfluenceSpace> spacesById = new HashMap<>();

  /**
   * Title and parent of every page a {@link #listPages} call of this client has seen - lets a full
   * sync resolve the Gliederungspfad of every page from the listing it already paid for, instead of
   * two more requests per page against the edition with a hard request budget.
   */
  private final Map<String, ConfluencePageSummary> knownPages = new HashMap<>();

  CloudConfluenceClient(ConfluenceHttp http) {
    super(http);
  }

  @Override
  public ConfluenceEdition edition() {
    return ConfluenceEdition.CLOUD;
  }

  @Override
  public void verifyCredentials() throws ConfluenceAccessException, InterruptedException {
    ConfluenceHttp.Response response =
        http.get(base() + API_V2 + "/spaces?limit=1", "die Space-Liste");
    if (response.status() == 404) {
      // Cloud answers 404 both for a missing API and for credentials it does not accept - it does
      // not tell an unauthenticated caller whether the path exists (ADR-0023, Entscheidung 2).
      throw new ConfluenceAccessException.Authentication(
          "Confluence Cloud hat die Anfrage nicht angenommen (HTTP 404 auf /wiki/api/v2): entweder"
              + " sind die Zugangsdaten (E-Mail und API-Token) nicht mehr gültig, oder unter dieser"
              + " Adresse antwortet kein Confluence Cloud.");
    }
    if (response.status() != 200) {
      throw http.failure(response.status(), "die Space-Liste");
    }
  }

  @Override
  public List<ConfluenceSpace> listSpaces() throws ConfluenceAccessException, InterruptedException {
    String resource = "die Space-Liste";
    List<ConfluenceSpace> spaces = new ArrayList<>();
    for (JsonNode node :
        listAll(base() + API_V2 + "/spaces?limit=" + pageSize(), resource, v2Reader(resource))) {
      spaces.add(remember(toSpace(node)));
    }
    return spaces;
  }

  @Override
  public List<ConfluencePageSummary> listPages(String spaceKey)
      throws ConfluenceAccessException, InterruptedException {
    ConfluenceSpace space = spaceByKey(spaceKey);
    String resource = "die Seiten des Space " + spaceKey;
    List<ConfluencePageSummary> pages = new ArrayList<>();
    String first =
        base()
            + API_V2
            + "/spaces/"
            + segment(space.id())
            + "/pages?status=current&limit="
            + pageSize();
    for (JsonNode node : listAll(first, resource, v2Reader(resource))) {
      ConfluencePageSummary summary =
          new ConfluencePageSummary(
              text(node, "id"),
              spaceKey,
              text(node, "title"),
              intOr(node.path("version").path("number"), -1),
              text(node, "parentId"));
      knownPages.put(summary.id(), summary);
      pages.add(summary);
    }
    return pages;
  }

  @Override
  public Optional<ConfluencePage> fetchPage(String pageId)
      throws ConfluenceAccessException, InterruptedException {
    String resource = "die Seite " + pageId;
    Optional<JsonNode> found =
        http.getJsonOrNotFound(
            base() + API_V2 + "/pages/" + segment(pageId) + "?body-format=storage", resource);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    JsonNode node = found.get();
    String spaceId = text(node, "spaceId");
    String spaceKey = spaceId == null ? null : spaceById(spaceId).key();
    ConfluencePageStatus status = ConfluencePageStatus.fromApi(text(node, "status"));
    List<String> ancestors =
        status == ConfluencePageStatus.CURRENT
            ? ancestorTitles(pageId, text(node, "parentId"))
            : List.of();
    return Optional.of(
        new ConfluencePage(
            text(node, "id"),
            spaceKey,
            text(node, "title"),
            intOr(node.path("version").path("number"), -1),
            status,
            ancestors,
            text(node.path("body").path("storage"), "value"),
            pageUrl(spaceKey, pageId),
            instantOrNull(text(node.path("version"), "createdAt"))));
  }

  /**
   * The Gliederungspfad, root first. Resolved from the pages a listing of this client already
   * delivered wherever possible; only a chain with an unknown link (an incremental run that never
   * listed the space) asks the instance - v2 ancestors are identifiers only, so titles come from
   * one bulk page request, ordered by walking {@code parentId} upwards so the result never depends
   * on the order the instance lists them in.
   */
  private List<String> ancestorTitles(String pageId, String parentId)
      throws ConfluenceAccessException, InterruptedException {
    if (parentId == null) {
      return List.of();
    }
    List<String> fromCache = chainFrom(parentId, knownPages);
    if (fromCache != null) {
      return fromCache;
    }
    String resource = "die Vorfahren der Seite " + pageId;
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode node :
        listAll(
            base() + API_V2 + "/pages/" + segment(pageId) + "/ancestors?limit=" + V2_MAX_LIMIT,
            resource,
            v2Reader(resource))) {
      String id = text(node, "id");
      if (id != null) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return List.of();
    }
    StringBuilder query = new StringBuilder(base() + API_V2 + "/pages?limit=" + V2_MAX_LIMIT);
    for (String id : ids) {
      query.append("&id=").append(encode(id));
    }
    Map<String, ConfluencePageSummary> fetched = new HashMap<>(knownPages);
    for (JsonNode node : listAll(query.toString(), resource, v2Reader(resource))) {
      String id = text(node, "id");
      fetched.put(
          id,
          new ConfluencePageSummary(
              id,
              null,
              text(node, "title"),
              intOr(node.path("version").path("number"), -1),
              text(node, "parentId")));
    }
    List<String> chain = chainFrom(parentId, fetched);
    return chain == null ? List.of() : chain;
  }

  /** Root-first titles from {@code parentId} upwards, or {@code null} if a link is unknown. */
  private static List<String> chainFrom(String parentId, Map<String, ConfluencePageSummary> known) {
    List<String> chain = new ArrayList<>();
    String cursor = parentId;
    int depth = 0;
    while (cursor != null) {
      ConfluencePageSummary page = known.get(cursor);
      if (page == null || depth++ >= MAX_ANCESTOR_DEPTH) {
        return null;
      }
      chain.add(0, page.title());
      cursor = page.parentId();
    }
    return chain;
  }

  @Override
  public List<ConfluenceAttachment> listAttachments(String pageId)
      throws ConfluenceAccessException, InterruptedException {
    String resource = "die Anhänge der Seite " + pageId;
    List<ConfluenceAttachment> attachments = new ArrayList<>();
    String first =
        base() + API_V2 + "/pages/" + segment(pageId) + "/attachments?limit=" + pageSize();
    for (JsonNode node : listAll(first, resource, v2Reader(resource))) {
      String download = text(node, "downloadLink");
      if (download == null) {
        download = text(node.path("_links"), "download");
      }
      attachments.add(
          ConfluenceAttachment.of(
              text(node, "id"),
              pageId,
              text(node, "title"),
              text(node, "mediaType"),
              longOr(node.path("fileSize"), -1L),
              intOr(node.path("version").path("number"), -1),
              download == null ? null : wikiLink(download, resource)));
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
            + SEARCH_V1
            + "?cql="
            + encode(changedPagesCql(spaceKeys, since))
            + "&limit="
            + pageSize();
    for (JsonNode node : listAll(first, resource, v2Reader(resource))) {
      String id = text(node.path("content"), "id");
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  @Override
  public String pageUrl(String spaceKey, String pageId) {
    return base() + WIKI + "/spaces/" + segment(spaceKey) + "/pages/" + segment(pageId);
  }

  private ConfluenceSpace spaceByKey(String key)
      throws ConfluenceAccessException, InterruptedException {
    ConfluenceSpace cached = spacesByKey.get(key);
    if (cached != null) {
      return cached;
    }
    JsonNode page =
        http.getJson(
            base() + API_V2 + "/spaces?keys=" + encode(key) + "&limit=1", "den Space " + key);
    List<JsonNode> results = results(page);
    if (results.isEmpty()) {
      throw new ConfluenceAccessException.NotFound(
          "Confluence kennt den Space "
              + key
              + " nicht oder die Zugangsdaten dürfen ihn nicht sehen (HTTP 404).");
    }
    return remember(toSpace(results.get(0)));
  }

  private ConfluenceSpace spaceById(String id)
      throws ConfluenceAccessException, InterruptedException {
    ConfluenceSpace cached = spacesById.get(id);
    if (cached != null) {
      return cached;
    }
    JsonNode node = http.getJson(base() + API_V2 + "/spaces/" + segment(id), "den Space " + id);
    return remember(toSpace(node));
  }

  private ConfluenceSpace remember(ConfluenceSpace space) {
    spacesByKey.put(space.key(), space);
    spacesById.put(space.id(), space);
    return space;
  }

  private static ConfluenceSpace toSpace(JsonNode node) {
    return new ConfluenceSpace(text(node, "id"), text(node, "key"), text(node, "name"));
  }

  /**
   * Reads a v2 (or v1 search) page: {@code results} plus a resolved, origin-checked {@code next}.
   */
  private PageReader v2Reader(String resource) {
    return page -> {
      String next = nextLink(page);
      return new Listing(results(page), next == null ? null : wikiLink(next, resource));
    };
  }

  /**
   * Resolves a Cloud link against the instance: host-root links ({@code /wiki/...}) and
   * context-relative links ({@code /api/v2/...}, {@code /rest/...}, {@code /download/...}) become
   * absolute; an absolute link must stay on the instance's origin.
   */
  private String wikiLink(String link, String resource) throws ConfluenceAccessException {
    if (link.startsWith("http://") || link.startsWith("https://")) {
      return http.resolveLink(link, resource);
    }
    String path = link.startsWith("/") ? link : "/" + link;
    if (!path.startsWith(WIKI + "/")) {
      path = WIKI + path;
    }
    return http.resolveLink(path, resource);
  }
}
