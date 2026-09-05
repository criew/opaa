package io.opaa.indexing.source.confluence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.ConfluenceEdition;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * The common test double of both editions (ADR-0023): one in-memory model of spaces, pages
 * (hierarchy, versions, trash, view restrictions), attachments and tokens with per-space read
 * rights, served either as Confluence Cloud ({@code /wiki/api/v2}, {@code /wiki/rest/api/search},
 * {@code /_edge/tenant_info}, Basic auth, cursor links) or as Data Center ({@code /rest/api},
 * {@code /status}, Bearer auth, offset links, {@code expand}, optionally under a context path).
 * Records every request; can throttle the next requests with a configurable status and {@code
 * Retry-After}, point the next {@code _links.next} at a foreign host, and refuse attachment
 * downloads.
 *
 * <p>Link shapes follow the vendor documentation: Cloud v2 gives {@code _links.next} from the host
 * root ({@code /wiki/api/v2/...}) for spaces and context-relative ({@code /api/v2/...}) for pages,
 * the v1 search a context-relative cursor link ({@code /rest/api/search?cursor=...}), {@code
 * downloadLink} context-relative with volatile query parameters; Data Center gives {@code
 * _links.next} and {@code _links.download} relative to {@code _links.base} (the instance address
 * including the context path).
 */
public final class FakeConfluenceServer implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern CQL_SINCE =
      Pattern.compile("lastmodified >= now\\(\"([+-])(\\d+)m\"\\)");
  private static final Pattern CQL_SPACES = Pattern.compile("space in \\(([^)]*)\\)");

  public record Space(String id, String key, String name) {}

  public static final class Page {
    final String id;
    String spaceKey;
    String title;
    int version;
    final String parentId;
    String body;
    String status = "current";
    Instant lastModified;
    boolean restricted;

    /** Listed, but every fetch answers 404 - a right revoked between listing and fetch. */
    boolean fetchHidden;

    Page(
        String id,
        String spaceKey,
        String title,
        int version,
        String parentId,
        String body,
        Instant lastModified) {
      this.id = id;
      this.spaceKey = spaceKey;
      this.title = title;
      this.version = version;
      this.parentId = parentId;
      this.body = body;
      this.lastModified = lastModified;
    }
  }

  public record Attachment(
      String id, String pageId, String fileName, String mediaType, byte[] bytes, int version) {}

  private final ConfluenceEdition edition;
  private final String contextPath;
  private final HttpServer server;
  private final Map<String, Space> spacesById = new LinkedHashMap<>();
  private final Map<String, Space> spacesByKey = new LinkedHashMap<>();
  private final Map<String, Page> pages = new LinkedHashMap<>();
  private final Map<String, List<Attachment>> attachmentsByPage = new LinkedHashMap<>();

  /** token -> readable space keys ({@code null} value = every space). */
  private final Map<String, Set<String>> tokens = new LinkedHashMap<>();

  private volatile boolean dataCenterApiHidden;

  private final Map<String, String> cloudEmailByToken = new LinkedHashMap<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger throttleRemaining = new AtomicInteger();
  private volatile String throttleRetryAfter = "1";
  private volatile int throttleStatus = 429;
  private volatile String foreignNextLink;
  private volatile int attachmentDownloadStatus = 200;

  public FakeConfluenceServer(ConfluenceEdition edition) throws IOException {
    this(edition, "");
  }

  /** {@code contextPath} such as {@code /confluence} - Data Center only, empty for Cloud. */
  public FakeConfluenceServer(ConfluenceEdition edition, String contextPath) throws IOException {
    this.edition = edition;
    this.contextPath = contextPath;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  /** The address a user would enter: host root plus context path (Data Center). */
  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + contextPath;
  }

  public List<String> requests() {
    return requests;
  }

  // ---- model -----------------------------------------------------------------------------------

  public Space addSpace(String id, String key, String name) {
    Space space = new Space(id, key, name);
    spacesById.put(id, space);
    spacesByKey.put(key, space);
    return space;
  }

  public Page addPage(
      String id, String spaceKey, String title, String parentId, String body, Instant modified) {
    Page page = new Page(id, spaceKey, title, 1, parentId, body, modified);
    pages.put(id, page);
    return page;
  }

  public void updatePage(String id, String newBody, Instant modified) {
    Page page = pages.get(id);
    page.body = newBody;
    page.version++;
    page.lastModified = modified;
  }

  /** Moves a page into another space - on Cloud that changes its identity URL. */
  public void movePage(String id, String newSpaceKey, Instant modified) {
    Page page = pages.get(id);
    page.spaceKey = newSpaceKey;
    page.version++;
    page.lastModified = modified;
  }

  public void trashPage(String id) {
    pages.get(id).status = "trashed";
  }

  /**
   * A view restriction the test token cannot satisfy: hidden from listings, {@code 404} on fetch.
   */
  public void restrictPage(String id) {
    pages.get(id).restricted = true;
  }

  /** The page stays in listings but answers {@code 404} on fetch - listing and fetch disagree. */
  public void hideFromFetch(String id) {
    pages.get(id).fetchHidden = true;
  }

  public Attachment addAttachment(
      String id, String pageId, String fileName, String mediaType, byte[] bytes) {
    Attachment attachment = new Attachment(id, pageId, fileName, mediaType, bytes, 1);
    attachmentsByPage.computeIfAbsent(pageId, k -> new ArrayList<>()).add(attachment);
    return attachment;
  }

  /** Registers a token limited to {@code readableSpaceKeys} ({@code null} = all). */
  public ConfluenceCredentials addToken(String email, String token, Set<String> readableSpaceKeys) {
    tokens.put(token, readableSpaceKeys);
    cloudEmailByToken.put(token, email);
    return edition == ConfluenceEdition.CLOUD
        ? new ConfluenceCredentials.CloudApiToken(email, token)
        : new ConfluenceCredentials.DataCenterPersonalAccessToken(token);
  }

  /** The next {@code count} requests answer {@code 429} with the given {@code Retry-After}. */
  public void throttleNext(int count, String retryAfter) {
    throttleNext(count, retryAfter, 429);
  }

  /** The next {@code count} requests answer {@code status} with the given {@code Retry-After}. */
  public void throttleNext(int count, String retryAfter, int status) {
    throttleRetryAfter = retryAfter;
    throttleStatus = status;
    throttleRemaining.set(count);
  }

  /** Every listing's {@code _links.next} points at {@code absoluteUrl} from now on. */
  public void pointNextLinksAt(String absoluteUrl) {
    foreignNextLink = absoluteUrl;
  }

  /** Attachment downloads answer {@code status} from now on. */
  /** From now on nothing under {@code /rest/api} exists - a host that is no Data Center at all. */
  public void hideDataCenterApi() {
    dataCenterApiHidden = true;
  }

  public void refuseAttachmentDownloads(int status) {
    attachmentDownloadStatus = status;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- dispatch --------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws IOException {
    URI uri = exchange.getRequestURI();
    requests.add(uri.toString());
    try {
      if (throttleRemaining.get() > 0 && throttleRemaining.decrementAndGet() >= 0) {
        exchange.getResponseHeaders().add("Retry-After", throttleRetryAfter);
        send(exchange, throttleStatus, "{\"message\":\"slow down\"}");
        return;
      }
      String path = uri.getPath();
      Map<String, List<String>> query = query(uri.getRawQuery());
      if (!path.startsWith(contextPath + "/") && !path.equals(contextPath)) {
        send(exchange, 404, "<html>not here</html>");
        return;
      }
      String local = path.substring(contextPath.length());
      if (edition == ConfluenceEdition.CLOUD) {
        handleCloud(exchange, local, query);
      } else {
        handleDataCenter(exchange, local, query);
      }
    } catch (RuntimeException e) {
      send(exchange, 500, "{\"message\":\"fake failure: " + e.getClass().getSimpleName() + "\"}");
    } finally {
      exchange.close();
    }
  }

  // ---- Cloud -----------------------------------------------------------------------------------

  private void handleCloud(HttpExchange ex, String path, Map<String, List<String>> q)
      throws IOException {
    if (path.equals("/_edge/tenant_info")) {
      send(ex, 200, "{\"cloudId\":\"fake-cloud-id\"}");
      return;
    }
    if (path.equals("/status")) {
      send(ex, 200, "OK");
      return;
    }
    if (!path.startsWith("/wiki/")) {
      send(ex, 404, "<html>not here</html>");
      return;
    }
    String token = authenticateCloud(ex);
    if (token == null) {
      // unauthenticated Cloud requests do not reveal whether the path exists
      send(ex, ex.getRequestHeaders().containsKey("Authorization") ? 401 : 404, cloudError(404));
      return;
    }
    Set<String> readable = tokens.get(token);
    int limit = intParam(q, "limit", 25);
    int cursor = intParam(q, "cursor", 0);

    if (path.equals("/wiki/api/v2/spaces")) {
      List<String> keys = q.getOrDefault("keys", List.of());
      List<Map<String, Object>> all = new ArrayList<>();
      for (Space s : spacesById.values()) {
        if (!canRead(readable, s.key())) {
          continue;
        }
        if (!keys.isEmpty()
            && keys.stream().noneMatch(k -> List.of(k.split(",")).contains(s.key()))) {
          continue;
        }
        all.add(spaceJson(s));
      }
      // spaces: next from the host root
      sendCursorPage(ex, all, cursor, limit, "/wiki/api/v2/spaces", q);
      return;
    }
    Matcher m;
    if ((m = Pattern.compile("^/wiki/api/v2/spaces/(\\d+)$").matcher(path)).matches()) {
      Space s = spacesById.get(m.group(1));
      if (s == null || !canRead(readable, s.key())) {
        send(ex, 404, cloudError(404));
        return;
      }
      send(ex, 200, json(spaceJson(s)));
      return;
    }
    if ((m = Pattern.compile("^/wiki/api/v2/spaces/(\\d+)/pages$").matcher(path)).matches()) {
      Space s = spacesById.get(m.group(1));
      if (s == null || !canRead(readable, s.key())) {
        send(ex, 404, cloudError(404));
        return;
      }
      List<Map<String, Object>> all = new ArrayList<>();
      for (Page p : pages.values()) {
        if (p.spaceKey.equals(s.key()) && isVisible(p)) {
          all.add(cloudPageJson(p, false));
        }
      }
      // pages: next relative to the /wiki context
      sendCursorPage(ex, all, cursor, limit, path.substring("/wiki".length()), q);
      return;
    }
    if (path.equals("/wiki/api/v2/pages")) {
      List<Map<String, Object>> all = new ArrayList<>();
      for (String id : q.getOrDefault("id", List.of())) {
        Page p = pages.get(id);
        if (p != null && isVisible(p) && canRead(readable, p.spaceKey)) {
          all.add(cloudPageJson(p, false));
        }
      }
      sendCursorPage(ex, all, cursor, limit, path, q);
      return;
    }
    if ((m = Pattern.compile("^/wiki/api/v2/pages/(\\d+)$").matcher(path)).matches()) {
      Page p = pages.get(m.group(1));
      if (p == null || p.restricted || p.fetchHidden || !canRead(readable, p.spaceKey)) {
        send(ex, 404, cloudError(404));
        return;
      }
      send(ex, 200, json(cloudPageJson(p, q.containsKey("body-format"))));
      return;
    }
    if ((m = Pattern.compile("^/wiki/api/v2/pages/(\\d+)/ancestors$").matcher(path)).matches()) {
      Page p = pages.get(m.group(1));
      if (p == null || p.restricted || p.fetchHidden || !canRead(readable, p.spaceKey)) {
        send(ex, 404, cloudError(404));
        return;
      }
      // Cloud lists ancestors nearest-first; identifiers only.
      List<Map<String, Object>> all = new ArrayList<>();
      String cursorId = p.parentId;
      while (cursorId != null && pages.containsKey(cursorId)) {
        Page a = pages.get(cursorId);
        all.add(Map.of("id", a.id, "type", "page"));
        cursorId = a.parentId;
      }
      sendCursorPage(ex, all, cursor, limit, path, q);
      return;
    }
    if ((m = Pattern.compile("^/wiki/api/v2/pages/(\\d+)/attachments$").matcher(path)).matches()) {
      Page p = pages.get(m.group(1));
      if (p == null || p.restricted || p.fetchHidden || !canRead(readable, p.spaceKey)) {
        send(ex, 404, cloudError(404));
        return;
      }
      List<Map<String, Object>> all = new ArrayList<>();
      for (Attachment a : attachmentsByPage.getOrDefault(p.id, List.of())) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", a.id());
        node.put("status", "current");
        node.put("title", a.fileName());
        node.put("mediaType", a.mediaType());
        node.put("fileSize", a.bytes().length);
        node.put("pageId", p.id);
        node.put("version", Map.of("number", a.version()));
        node.put(
            "downloadLink",
            "/download/attachments/"
                + p.id
                + "/"
                + a.fileName()
                + "?version="
                + a.version()
                + "&modificationDate=1700000000000&cacheVersion=1&api=v2");
        all.add(node);
      }
      sendCursorPage(ex, all, cursor, limit, path, q);
      return;
    }
    if ((m = Pattern.compile("^/wiki/download/attachments/(\\d+)/(.+)$").matcher(path)).matches()) {
      serveAttachment(ex, m.group(1), m.group(2), readable);
      return;
    }
    if (path.equals("/wiki/rest/api/search")) {
      List<Map<String, Object>> all = new ArrayList<>();
      for (Page p : searchPages(q, readable)) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("id", p.id);
        content.put("type", "page");
        content.put("status", p.status);
        content.put("title", p.title);
        String expand = String.join(",", q.getOrDefault("expand", List.of()));
        if (expand.contains("content.version")) {
          content.put("version", Map.of("number", p.version));
        }
        if (expand.contains("content.space")) {
          content.put("space", Map.of("key", p.spaceKey));
        }
        all.add(Map.of("content", content));
      }
      // v1 search: cursor link relative to the /wiki context
      sendCursorPage(ex, all, cursor, limit, "/rest/api/search", q);
      return;
    }
    send(ex, 404, cloudError(404));
  }

  private String authenticateCloud(HttpExchange ex) {
    String header = ex.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Basic ")) {
      return null;
    }
    String decoded =
        new String(
            Base64.getDecoder().decode(header.substring("Basic ".length())),
            StandardCharsets.UTF_8);
    int sep = decoded.indexOf(':');
    if (sep <= 0) {
      return null;
    }
    String email = decoded.substring(0, sep);
    String token = decoded.substring(sep + 1);
    if (!tokens.containsKey(token) || !email.equals(cloudEmailByToken.get(token))) {
      return null;
    }
    return token;
  }

  private Map<String, Object> cloudPageJson(Page p, boolean withBody) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", p.id);
    node.put("status", p.status);
    node.put("title", p.title);
    node.put("spaceId", spacesByKey.get(p.spaceKey).id());
    node.put("parentId", p.parentId);
    node.put("parentType", p.parentId == null ? null : "page");
    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", p.version);
    version.put("createdAt", p.lastModified.toString());
    node.put("version", version);
    if (withBody) {
      node.put("body", Map.of("storage", Map.of("representation", "storage", "value", p.body)));
    } else {
      node.put("body", Map.of());
    }
    node.put(
        "_links",
        Map.of(
            "webui", "/spaces/" + p.spaceKey + "/pages/" + p.id + "/" + p.title.replace(' ', '+')));
    return node;
  }

  private static String cloudError(int status) {
    return "{\"errors\":[{\"status\":"
        + status
        + ",\"code\":\"NOT_FOUND\",\"title\":\"Not Found\"}]}";
  }

  private void sendCursorPage(
      HttpExchange ex,
      List<Map<String, Object>> all,
      int cursor,
      int limit,
      String nextPath,
      Map<String, List<String>> q)
      throws IOException {
    int end = Math.min(all.size(), cursor + limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("results", all.subList(Math.min(cursor, all.size()), end));
    Map<String, Object> links = new LinkedHashMap<>();
    links.put("base", "http://127.0.0.1:" + server.getAddress().getPort() + "/wiki");
    if (end < all.size()) {
      links.put(
          "next",
          nextLinkOr(nextPath + "?" + preserve(q, "cursor") + "cursor=" + end + "&limit=" + limit));
    }
    body.put("_links", links);
    send(ex, 200, json(body));
  }

  // ---- Data Center -----------------------------------------------------------------------------

  private void handleDataCenter(HttpExchange ex, String path, Map<String, List<String>> q)
      throws IOException {
    if (path.equals("/status")) {
      send(ex, 200, "{\"state\":\"RUNNING\"}");
      return;
    }
    if (dataCenterApiHidden || (!path.startsWith("/rest/api/") && !path.startsWith("/download/"))) {
      send(ex, 404, "<html>not here</html>");
      return;
    }
    String token = authenticateDataCenter(ex);
    // Like the real Data Center: an unknown token is not refused, the request runs
    // anonymously - and anonymous reads nothing here.
    Set<String> readable = token == null ? Set.of() : tokens.get(token);
    if (path.equals("/rest/api/user/current")) {
      send(
          ex,
          200,
          token == null
              ? "{\"type\":\"anonymous\",\"username\":\"anonymous\",\"displayName\":\"Anonymous\"}"
              : "{\"type\":\"known\",\"username\":\"dienstkonto\",\"userKey\":\"8a7f\","
                  + "\"displayName\":\"Dienstkonto\"}");
      return;
    }
    int limit = intParam(q, "limit", 25);
    int start = intParam(q, "start", 0);

    if (path.equals("/rest/api/space")) {
      List<Map<String, Object>> all = new ArrayList<>();
      for (Space s : spacesById.values()) {
        if (canRead(readable, s.key())) {
          all.add(spaceJson(s));
        }
      }
      sendOffsetPage(ex, all, start, limit, path, q);
      return;
    }
    if (path.equals("/rest/api/content")) {
      String spaceKey = first(q, "spaceKey");
      Space s = spaceKey == null ? null : spacesByKey.get(spaceKey);
      if (s == null) {
        send(ex, 404, "{\"statusCode\":404,\"message\":\"No space with key\"}");
        return;
      }
      if (!canRead(readable, s.key())) {
        send(ex, 403, "{\"statusCode\":403,\"message\":\"No permission to view space\"}");
        return;
      }
      List<Map<String, Object>> all = new ArrayList<>();
      for (Page p : pages.values()) {
        if (p.spaceKey.equals(s.key()) && isVisible(p)) {
          all.add(dcPageJson(p, q.getOrDefault("expand", List.of("")).get(0)));
        }
      }
      sendOffsetPage(ex, all, start, limit, path, q);
      return;
    }
    if (path.equals("/rest/api/content/search")) {
      List<Map<String, Object>> all = new ArrayList<>();
      for (Page p : searchPages(q, readable)) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", p.id);
        node.put("type", "page");
        node.put("status", p.status);
        node.put("title", p.title);
        String expand = String.join(",", q.getOrDefault("expand", List.of()));
        if (expand.contains("version")) {
          node.put("version", Map.of("number", p.version));
        }
        if (expand.contains("space")) {
          node.put("space", Map.of("key", p.spaceKey));
        }
        all.add(node);
      }
      sendOffsetPage(ex, all, start, limit, path, q);
      return;
    }
    Matcher m;
    if ((m = Pattern.compile("^/rest/api/content/(\\d+)$").matcher(path)).matches()) {
      Page p = pages.get(m.group(1));
      boolean wantTrashed = "trashed".equals(first(q, "status"));
      if (p == null || p.restricted || p.fetchHidden || !canRead(readable, p.spaceKey)) {
        send(ex, 404, "{\"statusCode\":404,\"message\":\"No content with id\"}");
        return;
      }
      if (wantTrashed != p.status.equals("trashed")) {
        send(ex, 404, "{\"statusCode\":404,\"message\":\"No content with id\"}");
        return;
      }
      send(ex, 200, json(dcPageJson(p, q.getOrDefault("expand", List.of("")).get(0))));
      return;
    }
    if ((m = Pattern.compile("^/rest/api/content/(\\d+)/child/attachment$").matcher(path))
        .matches()) {
      Page p = pages.get(m.group(1));
      if (p == null || p.restricted || p.fetchHidden || !canRead(readable, p.spaceKey)) {
        send(ex, 404, "{\"statusCode\":404,\"message\":\"No content with id\"}");
        return;
      }
      List<Map<String, Object>> all = new ArrayList<>();
      for (Attachment a : attachmentsByPage.getOrDefault(p.id, List.of())) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", a.id());
        node.put("type", "attachment");
        node.put("status", "current");
        node.put("title", a.fileName());
        node.put("extensions", Map.of("mediaType", a.mediaType(), "fileSize", a.bytes().length));
        node.put("version", Map.of("number", a.version()));
        // relative to _links.base, i.e. without the context path
        node.put(
            "_links",
            Map.of(
                "download",
                "/download/attachments/"
                    + p.id
                    + "/"
                    + a.fileName()
                    + "?version="
                    + a.version()
                    + "&modificationDate=1700000000000&api=v2"));
        all.add(node);
      }
      sendOffsetPage(ex, all, start, limit, path, q);
      return;
    }
    if ((m = Pattern.compile("^/download/attachments/(\\d+)/(.+)$").matcher(path)).matches()) {
      serveAttachment(ex, m.group(1), m.group(2), readable);
      return;
    }
    send(ex, 404, "{\"statusCode\":404,\"message\":\"null for uri\"}");
  }

  private String authenticateDataCenter(HttpExchange ex) {
    String header = ex.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      return null;
    }
    String token = header.substring("Bearer ".length());
    return tokens.containsKey(token) ? token : null;
  }

  private Map<String, Object> dcPageJson(Page p, String expand) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", p.id);
    node.put("type", "page");
    node.put("status", p.status);
    node.put("title", p.title);
    if (expand.contains("space")) {
      node.put("space", spaceJson(spacesByKey.get(p.spaceKey)));
    }
    if (expand.contains("version")) {
      Map<String, Object> version = new LinkedHashMap<>();
      version.put("number", p.version);
      version.put("when", p.lastModified.toString());
      node.put("version", version);
    }
    if (expand.contains("ancestors")) {
      // Data Center lists ancestors root-first, with titles.
      List<Map<String, Object>> chain = new ArrayList<>();
      String cursorId = p.parentId;
      while (cursorId != null && pages.containsKey(cursorId)) {
        Page a = pages.get(cursorId);
        chain.add(0, Map.of("id", a.id, "type", "page", "title", a.title));
        cursorId = a.parentId;
      }
      node.put("ancestors", chain);
    }
    if (expand.contains("body.storage")) {
      node.put("body", Map.of("storage", Map.of("value", p.body, "representation", "storage")));
    }
    node.put("_links", Map.of("webui", "/display/" + p.spaceKey + "/" + p.title.replace(' ', '+')));
    return node;
  }

  private void sendOffsetPage(
      HttpExchange ex,
      List<Map<String, Object>> all,
      int start,
      int limit,
      String path,
      Map<String, List<String>> q)
      throws IOException {
    int end = Math.min(all.size(), start + limit);
    List<Map<String, Object>> slice = all.subList(Math.min(start, all.size()), end);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("results", slice);
    body.put("start", start);
    body.put("limit", limit);
    body.put("size", slice.size());
    Map<String, Object> links = new LinkedHashMap<>();
    links.put("base", baseUrl());
    links.put("context", contextPath);
    if (end < all.size()) {
      // relative to _links.base, i.e. without the context path
      links.put(
          "next",
          nextLinkOr(path + "?" + preserve(q, "start") + "start=" + end + "&limit=" + limit));
    }
    body.put("_links", links);
    send(ex, 200, json(body));
  }

  // ---- shared ----------------------------------------------------------------------------------

  private String nextLinkOr(String regular) {
    String foreign = foreignNextLink;
    return foreign != null ? foreign : regular;
  }

  private List<Page> searchPages(Map<String, List<String>> q, Set<String> readable) {
    String cql = first(q, "cql");
    if (cql == null) {
      return List.of();
    }
    Matcher since = CQL_SINCE.matcher(cql);
    // the relative window is evaluated against this server's own clock, like a real instance
    Instant from =
        since.find()
            ? Instant.now()
                .plus(
                    Duration.ofMinutes(
                        Long.parseLong(since.group(2)) * (since.group(1).equals("-") ? -1 : 1)))
            : Instant.MIN;
    Matcher spaces = CQL_SPACES.matcher(cql);
    Set<String> keys =
        spaces.find()
            ? Set.of(spaces.group(1).replace("\"", "").replace(" ", "").split(","))
            : spacesByKey.keySet();
    List<Page> hits = new ArrayList<>();
    for (Page p : pages.values()) {
      if (keys.contains(p.spaceKey)
          && canRead(readable, p.spaceKey)
          && isVisible(p)
          && !p.lastModified.isBefore(from)) {
        hits.add(p);
      }
    }
    return hits;
  }

  private void serveAttachment(
      HttpExchange ex, String pageId, String fileName, Set<String> readable) throws IOException {
    if (attachmentDownloadStatus != 200) {
      send(ex, attachmentDownloadStatus, "refused");
      return;
    }
    Page p = pages.get(pageId);
    if (p == null || !canRead(readable, p.spaceKey)) {
      send(ex, 404, "not found");
      return;
    }
    for (Attachment a : attachmentsByPage.getOrDefault(pageId, List.of())) {
      if (a.fileName().equals(URLDecoder.decode(fileName, StandardCharsets.UTF_8))) {
        ex.getResponseHeaders().add("Content-Type", a.mediaType());
        ex.sendResponseHeaders(200, a.bytes().length);
        try (OutputStream out = ex.getResponseBody()) {
          out.write(a.bytes());
        }
        return;
      }
    }
    send(ex, 404, "not found");
  }

  private static boolean canRead(Set<String> readable, String spaceKey) {
    return readable == null || readable.contains(spaceKey);
  }

  private static boolean isVisible(Page p) {
    return !p.restricted && p.status.equals("current");
  }

  private static Map<String, Object> spaceJson(Space s) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", s.id());
    node.put("key", s.key());
    node.put("name", s.name());
    node.put("type", "global");
    node.put("status", "current");
    return node;
  }

  private static String preserve(Map<String, List<String>> q, String except) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<String>> e : q.entrySet()) {
      if (e.getKey().equals(except) || e.getKey().equals("limit")) {
        continue;
      }
      for (String v : e.getValue()) {
        sb.append(e.getKey())
            .append('=')
            .append(URLEncoder.encode(v, StandardCharsets.UTF_8))
            .append('&');
      }
    }
    return sb.toString();
  }

  private static Map<String, List<String>> query(String rawQuery) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return map;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value =
          eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    return map;
  }

  private static String first(Map<String, List<String>> q, String key) {
    List<String> values = q.get(key);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  private static int intParam(Map<String, List<String>> q, String key, int fallback) {
    String value = first(q, key);
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String json(Object value) {
    return JSON.writeValueAsString(value);
  }

  private static void send(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (!ex.getResponseHeaders().containsKey("Content-Type")) {
      ex.getResponseHeaders()
          .add("Content-Type", body.startsWith("<") ? "text/html" : "application/json");
    }
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }
}
