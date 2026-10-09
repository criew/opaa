package io.opaa.msgraph;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Microsoft Graph v1.0 on loopback, held in memory: sites, document libraries, items and their
 * children, {@code delta} with {@code $top} and {@code token=latest}, and {@code /content}
 * redirecting to a second, pre-signed {@code https} host on another port. Switches answer the next
 * matching requests with a Graph error body, trickle an answer, or bend the links Graph hands out.
 * Microsoft offers no emulator; this double is what the tests trust about Graph, so its forms
 * follow the reference, not a tenant.
 */
public final class FakeGraphServer implements AutoCloseable {

  public static final String TOKEN = "eyJ0eXAiOiJKV1QifQ.fake-graph-token";

  /** Where {@code /content} redirects to. */
  public enum DownloadHost {
    /** The pre-signed https host at {@code 127.0.0.1}. */
    HTTPS,
    /** The same host by the name {@code localhost}, which an address validation resolves. */
    HTTPS_BY_NAME,
    /** A plain http host. */
    PLAIN_HTTP
  }

  /** How a delta link carries its token. */
  public enum DeltaLinkForm {
    /** {@code …/delta?token=…} */
    QUERY,
    /** {@code …/delta(token='…')} */
    FUNCTION,
    /** {@code …/delta(token=…)} */
    FUNCTION_UNQUOTED
  }

  /** A file or folder of a document library. */
  public static final class Item {
    public final String id;
    public final String driveId;
    public final boolean folder;
    public String name;
    public String parentId;
    public byte[] content = new byte[0];
    public boolean deleted;

    /** The {@code package} type, such as {@code oneNote}; {@code null} for a plain item. */
    public String packageType;

    /** Whether Microsoft flagged the item as malware ({@code malware} facet). */
    public boolean malware;

    /** The {@code file.mimeType} a file reports. */
    public String mimeType = "application/octet-stream";

    Item(String id, String driveId, boolean folder) {
      this.id = id;
      this.driveId = driveId;
      this.folder = folder;
    }
  }

  private record Site(String id, String hostname, String path, String name) {}

  private record Drive(String id, String siteId, String name, String driveType) {}

  private record Failure(String pathPart, int status, String code, String retryAfter, int[] left) {}

  /** A started delta enumeration: the entries still to hand out and where the log stood. */
  private record Pending(String driveId, List<Map<String, Object>> entries, int logPosition) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String VERSION = "/v1.0/";
  private static final String STORE_PASSWORD = "changeit";
  private static volatile KeyStore keyStore;

  private final HttpServer graph;
  private final HttpsServer download;
  private final HttpServer plainDownload;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final String presignedSecret = "tempauth-" + UUID.randomUUID();

  private final Map<String, Site> sites = new ConcurrentHashMap<>();
  private final Map<String, Drive> drives = new ConcurrentHashMap<>();
  private final Map<String, Item> items = new ConcurrentHashMap<>();
  private final Map<String, List<String>> changeLogs = new ConcurrentHashMap<>();
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final AtomicInteger tokenCounter = new AtomicInteger();
  private final List<Failure> failures = new CopyOnWriteArrayList<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> downloadAuthorizations = new CopyOnWriteArrayList<>();
  private final AtomicInteger plainDownloadHits = new AtomicInteger();
  private final Set<String> trickling = ConcurrentHashMap.newKeySet();
  private volatile boolean trickleDownloads;
  private final Set<String> acceptedTokens = ConcurrentHashMap.newKeySet();
  private final List<String> sentTokens = new CopyOnWriteArrayList<>();
  private volatile Set<String> grantedSites;
  private volatile boolean deltaTokensExpired;
  private volatile int pageTokensExpiredThrough;
  private volatile boolean repeatDeltaEntries;
  private final Map<String, String> staleParents = new ConcurrentHashMap<>();
  private volatile DownloadHost downloadHost = DownloadHost.HTTPS;
  private volatile DeltaLinkForm deltaLinkForm = DeltaLinkForm.QUERY;
  private volatile UnaryOperator<String> linkRewrite = UnaryOperator.identity();

  public FakeGraphServer() {
    acceptedTokens.add(TOKEN);
    try {
      graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      download = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      plainDownload = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      download.setHttpsConfigurator(new HttpsConfigurator(serverContext()));
    } catch (Exception e) {
      throw new IllegalStateException("cannot start the fake Graph", e);
    }
    graph.createContext("/", this::handleGraph);
    download.createContext("/", this::handleDownload);
    plainDownload.createContext(
        "/",
        exchange -> {
          plainDownloadHits.incrementAndGet();
          respond(exchange, 200, "text/plain", "plain".getBytes(StandardCharsets.UTF_8));
        });
    for (HttpServer server : List.of(graph, download, plainDownload)) {
      server.setExecutor(executor);
      server.start();
    }
  }

  /** Scheme, host and port of the Graph endpoint. */
  public URI origin() {
    return URI.create("http://127.0.0.1:" + graph.getAddress().getPort());
  }

  /** A client that trusts the pre-signed host's certificate and follows no redirect itself. */
  public HttpClient httpClient() {
    try {
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(keyStore());
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(5))
          .sslContext(context)
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("cannot build the test client", e);
    }
  }

  // --- arranging -------------------------------------------------------------------------------

  public void site(String id, String hostname, String path, String name) {
    sites.put(id, new Site(id, hostname, path, name));
  }

  /** A document library of {@code siteId} with its root folder {@code root-<driveId>}. */
  public void drive(String siteId, String driveId, String name, String driveType) {
    drives.put(driveId, new Drive(driveId, siteId, name, driveType));
    changeLogs.put(driveId, new CopyOnWriteArrayList<>());
    Item root = new Item(rootId(driveId), driveId, true);
    root.name = "root";
    items.put(root.id, root);
  }

  public static String rootId(String driveId) {
    return "root-" + driveId;
  }

  public Item folder(String driveId, String id, String name, String parentId) {
    return add(new Item(id, driveId, true), name, parentId);
  }

  public Item file(String driveId, String id, String name, String parentId, byte[] content) {
    Item file = add(new Item(id, driveId, false), name, parentId);
    file.content = content;
    return file;
  }

  public Item item(String id) {
    return items.get(id);
  }

  public void rename(String id, String name) {
    Item item = items.get(id);
    item.name = name;
    changed(item);
  }

  public void delete(String id) {
    Item item = items.get(id);
    item.deleted = true;
    changed(item);
  }

  /** Brings a deleted item back under its id, as a restore from the recycle bin does. */
  public void restore(String id) {
    Item item = items.get(id);
    item.deleted = false;
    changed(item);
  }

  /** New content for a file, logged as its change. */
  public void update(String id, byte[] content) {
    Item item = items.get(id);
    item.content = content;
    changed(item);
  }

  /** Moves an item under {@code parentId} of the same drive, logged at its old and new place. */
  public void move(String id, String parentId) {
    Item item = items.get(id);
    changed(item);
    item.parentId = parentId;
    changed(item);
  }

  /** Logs a change of {@code id} again, as Graph does for any change of an item. */
  public void touch(String id) {
    changed(items.get(id));
  }

  /**
   * From now on only the sites {@code siteIds} are granted to the application, as under {@code
   * Sites.Selected}: every other site, its drives and items answer {@code 403 accessDenied}, and so
   * does the site search.
   */
  public void grantOnly(String... siteIds) {
    grantedSites = Set.of(siteIds);
  }

  /** From now on also {@code token} is accepted. */
  public void alsoAccept(String token) {
    acceptedTokens.add(token);
  }

  /** The bearer token of every request to the Graph endpoint, in order. */
  public List<String> tokens() {
    return List.copyOf(sentTokens);
  }

  /** Every page token handed out so far answers 410; delta tokens and later ones stay valid. */
  public void expirePageTokens() {
    pageTokensExpiredThrough = tokenCounter.get();
  }

  /**
   * A new enumeration reports {@code id} first under its former parent {@code oldParentId}, then as
   * it is: repeated reports of one item may differ, and the last one is its state.
   */
  public void reportStaleParent(String id, String oldParentId) {
    staleParents.put(id, oldParentId);
  }

  /** Every delta entry is handed out twice in a row, as Graph may repeat an item. */
  public void repeatDeltaEntries() {
    repeatDeltaEntries = true;
  }

  /** The next {@code times} requests whose path contains {@code pathPart} fail this way. */
  public void failNext(String pathPart, int status, String code, String retryAfter, int times) {
    failures.add(new Failure(pathPart, status, code, retryAfter, new int[] {times}));
  }

  /** From now on only {@code token} is accepted. */
  public void acceptOnly(String token) {
    acceptedTokens.clear();
    acceptedTokens.add(token);
  }

  /** Every delta token handed out so far, and every page token of an enumeration, answers 410. */
  public void expireDeltaTokens() {
    deltaTokensExpired = true;
  }

  public void downloadHost(DownloadHost host) {
    downloadHost = host;
  }

  public void deltaLinkForm(DeltaLinkForm form) {
    deltaLinkForm = form;
  }

  /** Rewrites every {@code @odata.nextLink} and {@code @odata.deltaLink} before it is sent. */
  public void rewriteLinks(UnaryOperator<String> rewrite) {
    linkRewrite = rewrite;
  }

  /** Answers requests whose path contains {@code pathPart} one byte every 100 ms. */
  public void trickle(String pathPart) {
    trickling.add(pathPart);
  }

  /** Serves every download body one byte every 100 ms, headers first. */
  public void trickleDownloads() {
    trickleDownloads = true;
  }

  // --- observing -------------------------------------------------------------------------------

  /** Path and raw query of every request to the Graph endpoint. */
  public List<String> requests() {
    return List.copyOf(requests);
  }

  /** The {@code Authorization} header each request to the pre-signed host carried, or "(none)". */
  public List<String> downloadAuthorizations() {
    return List.copyOf(downloadAuthorizations);
  }

  public int plainDownloadHits() {
    return plainDownloadHits.get();
  }

  /** The short-lived credential the pre-signed addresses carry. */
  public String presignedSecret() {
    return presignedSecret;
  }

  // --- serving Graph ---------------------------------------------------------------------------

  private void handleGraph(HttpExchange exchange) throws IOException {
    URI uri = exchange.getRequestURI();
    requests.add(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
    try {
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      String sent =
          authorization != null && authorization.startsWith("Bearer ")
              ? authorization.substring("Bearer ".length())
              : "";
      sentTokens.add(sent);
      if (!acceptedTokens.contains(sent)) {
        error(exchange, 401, "InvalidAuthenticationToken", null);
        return;
      }
      String path = uri.getPath();
      if (!path.startsWith(VERSION)) {
        error(exchange, 400, "BadRequest", null);
        return;
      }
      if (fail(exchange, path)) {
        return;
      }
      if (!granted(path.substring(VERSION.length()))) {
        error(exchange, 403, "accessDenied", null);
        return;
      }
      route(exchange, path.substring(VERSION.length()), query(uri.getRawQuery()));
    } finally {
      exchange.close();
    }
  }

  /** Whether the application may reach what {@code path} names under the granted sites. */
  private boolean granted(String path) {
    Set<String> granted = grantedSites;
    if (granted == null) {
      return true;
    }
    if (path.equals("sites")) {
      return false;
    }
    Matcher site = Pattern.compile("sites/([^/:]+)(:(/.*))?(/.*)?").matcher(path);
    if (site.matches()) {
      String siteId =
          site.group(3) == null
              ? site.group(1)
              : sites.values().stream()
                  .filter(s -> s.hostname().equals(site.group(1)) && s.path().equals(site.group(3)))
                  .map(Site::id)
                  .findFirst()
                  .orElse(null);
      return siteId == null || granted.contains(siteId);
    }
    Matcher drive = Pattern.compile("drives/([^/]+).*").matcher(path);
    if (drive.matches()) {
      Drive known = drives.get(drive.group(1));
      return known == null || granted.contains(known.siteId());
    }
    return true;
  }

  private void route(HttpExchange exchange, String path, Map<String, String> query)
      throws IOException {
    Matcher matcher;
    if (path.equals("sites")) {
      String search = query.getOrDefault("search", "").toLowerCase();
      List<Map<String, Object>> found = new ArrayList<>();
      sites.values().stream()
          .filter(site -> site.name().toLowerCase().contains(search))
          .forEach(site -> found.add(site(site)));
      json(exchange, path, query, Map.of("value", found));
    } else if ((matcher = Pattern.compile("sites/([^/:]+):(/.*)").matcher(path)).matches()) {
      String hostname = matcher.group(1);
      String sitePath = matcher.group(2);
      Site site =
          sites.values().stream()
              .filter(s -> s.hostname().equals(hostname) && s.path().equals(sitePath))
              .findFirst()
              .orElse(null);
      answer(exchange, site == null ? null : site(site));
    } else if ((matcher = Pattern.compile("sites/([^/]+)/drives").matcher(path)).matches()) {
      String siteId = matcher.group(1);
      if (!sites.containsKey(siteId)) {
        error(exchange, 404, "itemNotFound", null);
        return;
      }
      List<Map<String, Object>> list = new ArrayList<>();
      drives.values().stream()
          .filter(drive -> drive.siteId().equals(siteId))
          .forEach(drive -> list.add(drive(drive)));
      json(exchange, path, query, Map.of("value", list));
    } else if ((matcher = Pattern.compile("sites/([^/]+)").matcher(path)).matches()) {
      Site site = sites.get(matcher.group(1));
      answer(exchange, site == null ? null : site(site));
    } else if ((matcher = Pattern.compile("drives/([^/]+)/root/delta").matcher(path)).matches()) {
      delta(exchange, path, matcher.group(1), query);
    } else if ((matcher = Pattern.compile("drives/([^/]+)/root/children").matcher(path))
        .matches()) {
      children(exchange, path, rootId(matcher.group(1)), query);
    } else if ((matcher = Pattern.compile("drives/([^/]+)/items/([^/]+)/children").matcher(path))
        .matches()) {
      children(exchange, path, matcher.group(2), query);
    } else if ((matcher = Pattern.compile("drives/([^/]+)/items/([^/]+)/content").matcher(path))
        .matches()) {
      Item item = visible(matcher.group(1), matcher.group(2));
      if (item == null || item.folder) {
        error(exchange, 404, "itemNotFound", null);
        return;
      }
      exchange.getResponseHeaders().set("Location", downloadAddress(item.id));
      exchange.sendResponseHeaders(302, -1);
    } else if ((matcher = Pattern.compile("drives/([^/]+)/items/([^/]+)").matcher(path))
        .matches()) {
      Item item = visible(matcher.group(1), matcher.group(2));
      answer(exchange, item == null ? null : item(item));
    } else if ((matcher = Pattern.compile("drives/([^/]+)").matcher(path)).matches()) {
      Drive drive = drives.get(matcher.group(1));
      answer(exchange, drive == null ? null : drive(drive));
    } else {
      error(exchange, 400, "invalidRequest", null);
    }
  }

  private void delta(HttpExchange exchange, String path, String driveId, Map<String, String> query)
      throws IOException {
    List<String> log = changeLogs.get(driveId);
    if (log == null) {
      error(exchange, 404, "itemNotFound", null);
      return;
    }
    String token = query.get("token");
    int top = Integer.parseInt(query.getOrDefault("$top", "200"));
    if ("latest".equals(token)) {
      deltaPage(exchange, path, driveId, new Pending(driveId, List.of(), log.size()), top);
      return;
    }
    if (token != null
        && (deltaTokensExpired
            || (token.startsWith("p")
                && Integer.parseInt(token.substring(1)) <= pageTokensExpiredThrough))) {
      error(exchange, 410, "resyncRequired", null);
      return;
    }
    Pending start;
    if (token == null) {
      List<Map<String, Object>> all = new ArrayList<>();
      items.values().stream()
          .filter(item -> item.driveId.equals(driveId) && !item.deleted)
          .sorted(
              (a, b) ->
                  depth(a) != depth(b)
                      ? Integer.compare(depth(a), depth(b))
                      : a.name.compareTo(b.name))
          .forEach(item -> all.add(item(item)));
      start = new Pending(driveId, repeated(withStaleParents(all)), log.size());
    } else if (token.startsWith("d")) {
      int position = Integer.parseInt(token.substring(1));
      List<Map<String, Object>> changed = new ArrayList<>();
      new LinkedHashSet<>(log.subList(position, log.size()))
          .forEach(id -> changed.add(item(items.get(id))));
      start = new Pending(driveId, repeated(changed), log.size());
    } else {
      start = pending.get(token);
      if (start == null || !start.driveId().equals(driveId)) {
        error(exchange, 410, "resyncRequired", null);
        return;
      }
    }
    deltaPage(exchange, path, driveId, start, top);
  }

  /** {@code entries}, each preceded by a stale report where {@link #reportStaleParent} asks. */
  private List<Map<String, Object>> withStaleParents(List<Map<String, Object>> entries) {
    List<Map<String, Object>> reported = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      String oldParent = staleParents.get((String) entry.get("id"));
      if (oldParent != null) {
        Map<String, Object> stale = new LinkedHashMap<>(entry);
        stale.put(
            "parentReference",
            Map.of("driveId", items.get(entry.get("id")).driveId, "id", oldParent));
        reported.add(stale);
      }
      reported.add(entry);
    }
    return reported;
  }

  /** {@code entries}, each twice in a row while {@link #repeatDeltaEntries()} is on. */
  private List<Map<String, Object>> repeated(List<Map<String, Object>> entries) {
    if (!repeatDeltaEntries) {
      return entries;
    }
    List<Map<String, Object>> repeated = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      repeated.add(entry);
      repeated.add(entry);
    }
    return repeated;
  }

  private void deltaPage(HttpExchange exchange, String path, String driveId, Pending from, int top)
      throws IOException {
    List<Map<String, Object>> entries = from.entries();
    int end = Math.min(entries.size(), top);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", entries.subList(0, end));
    if (end < entries.size()) {
      String next = "p" + tokenCounter.incrementAndGet();
      pending.put(
          next, new Pending(driveId, entries.subList(end, entries.size()), from.logPosition()));
      body.put("@odata.nextLink", deltaLink(path, next));
    } else {
      body.put("@odata.deltaLink", deltaLink(path, "d" + from.logPosition()));
    }
    json(exchange, path, Map.of(), body);
  }

  private String deltaLink(String path, String token) {
    String link =
        switch (deltaLinkForm) {
          case QUERY -> origin() + VERSION + path + "?token=" + token;
          case FUNCTION -> origin() + VERSION + path + "(token='" + token + "')";
          case FUNCTION_UNQUOTED -> origin() + VERSION + path + "(token=" + token + ")";
        };
    return linkRewrite.apply(link);
  }

  private void children(
      HttpExchange exchange, String path, String parentId, Map<String, String> query)
      throws IOException {
    Item parent = items.get(parentId);
    if (parent == null || parent.deleted || !parent.folder) {
      error(exchange, 404, "itemNotFound", null);
      return;
    }
    List<Map<String, Object>> all = new ArrayList<>();
    items.values().stream()
        .filter(item -> parentId.equals(item.parentId) && !item.deleted)
        .sorted((a, b) -> a.name.compareTo(b.name))
        .forEach(item -> all.add(item(item)));
    int top = Integer.parseInt(query.getOrDefault("$top", "200"));
    String skip = query.get("$skiptoken");
    int start = skip == null ? 0 : Integer.parseInt(skip.substring(1));
    int end = Math.min(all.size(), start + top);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", all.subList(start, end));
    if (end < all.size()) {
      body.put(
          "@odata.nextLink",
          linkRewrite.apply(origin() + VERSION + path + "?$top=" + top + "&$skiptoken=s" + end));
    }
    json(exchange, path, query, body);
  }

  private boolean fail(HttpExchange exchange, String path) throws IOException {
    for (Failure failure : failures) {
      if (path.contains(failure.pathPart()) && failure.left()[0] > 0) {
        failure.left()[0]--;
        error(exchange, failure.status(), failure.code(), failure.retryAfter());
        return true;
      }
    }
    return false;
  }

  // --- serving the pre-signed host -------------------------------------------------------------

  private String downloadAddress(String itemId) {
    String suffix = "/blob/" + itemId + "?tempauth=" + presignedSecret;
    return switch (downloadHost) {
      case HTTPS_BY_NAME -> "https://localhost:" + download.getAddress().getPort() + suffix;
      case PLAIN_HTTP -> "http://127.0.0.1:" + plainDownload.getAddress().getPort() + suffix;
      case HTTPS -> "https://127.0.0.1:" + download.getAddress().getPort() + suffix;
    };
  }

  private void handleDownload(HttpExchange exchange) throws IOException {
    try {
      URI uri = exchange.getRequestURI();
      if (fail(exchange, uri.getPath())) {
        return;
      }
      Matcher matcher = Pattern.compile("/blob/([^/]+)").matcher(uri.getPath());
      if (!matcher.matches() || !("tempauth=" + presignedSecret).equals(uri.getRawQuery())) {
        recordAuthorization(exchange);
        respond(exchange, 403, "text/plain", "denied".getBytes(StandardCharsets.UTF_8));
        return;
      }
      serveBlob(exchange, matcher.group(1));
    } finally {
      exchange.close();
    }
  }

  /** Serves the content of {@code itemId}, noting the {@code Authorization} the request carried. */
  private void serveBlob(HttpExchange exchange, String itemId) throws IOException {
    recordAuthorization(exchange);
    Item item = items.get(itemId);
    if (item == null) {
      respond(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      if (trickleDownloads) {
        for (byte b : item.content) {
          out.write(b);
          out.flush();
          TimeUnit.MILLISECONDS.sleep(100);
        }
      } else {
        out.write(item.content);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      if (!trickleDownloads) {
        throw e;
      }
    }
  }

  private void recordAuthorization(HttpExchange exchange) {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    downloadAuthorizations.add(authorization == null ? "(none)" : authorization);
  }

  // --- forms -----------------------------------------------------------------------------------

  private Item add(Item item, String name, String parentId) {
    item.name = name;
    item.parentId = parentId;
    items.put(item.id, item);
    changed(item);
    return item;
  }

  /** Logs {@code item} and, as Graph does, every folder above it. */
  private void changed(Item item) {
    List<String> log = changeLogs.get(item.driveId);
    for (Item current = item; current != null; current = items.get(current.parentId)) {
      log.add(current.id);
      if (current.parentId == null) {
        break;
      }
    }
  }

  private int depth(Item item) {
    int depth = 0;
    for (Item current = item; current.parentId != null; current = items.get(current.parentId)) {
      depth++;
    }
    return depth;
  }

  private Item visible(String driveId, String id) {
    Item item = items.get(id.equals("root") ? rootId(driveId) : id);
    return item == null || item.deleted || !item.driveId.equals(driveId) ? null : item;
  }

  private Map<String, Object> site(Site site) {
    return Map.of(
        "id",
        site.id(),
        "displayName",
        site.name(),
        "webUrl",
        "https://" + site.hostname() + site.path());
  }

  private Map<String, Object> drive(Drive drive) {
    return Map.of("id", drive.id(), "name", drive.name(), "driveType", drive.driveType());
  }

  private Map<String, Object> item(Item item) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", item.id);
    if (item.deleted) {
      json.put("deleted", Map.of("state", "deleted"));
      json.put("parentReference", Map.of("driveId", item.driveId));
      return json;
    }
    json.put("name", item.name);
    Map<String, Object> parent = new LinkedHashMap<>();
    parent.put("driveId", item.driveId);
    if (item.parentId != null) {
      parent.put("id", item.parentId);
    }
    json.put("parentReference", parent);
    if (item.parentId == null) {
      json.put("root", Map.of());
    }
    if (item.packageType != null) {
      json.put("package", Map.of("type", item.packageType));
    } else if (item.folder) {
      json.put("folder", Map.of());
    } else {
      json.put("size", item.content.length);
      json.put(
          "file",
          Map.of(
              "mimeType", item.mimeType, "hashes", Map.of("quickXorHash", digest(item.content))));
    }
    if (item.malware) {
      json.put("malware", Map.of("description", "fake malware"));
    }
    json.put("cTag", "c:" + digest(item.content) + item.name);
    json.put("lastModifiedDateTime", "2026-10-01T10:00:00Z");
    return json;
  }

  /** A stand-in for quickXorHash: same length, not the same function. */
  private static String digest(byte[] content) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void answer(HttpExchange exchange, Map<String, Object> body) throws IOException {
    if (body == null) {
      error(exchange, 404, "itemNotFound", null);
    } else {
      json(exchange, exchange.getRequestURI().getPath(), Map.of(), body);
    }
  }

  private void json(HttpExchange exchange, String path, Map<String, String> query, Object body)
      throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    if (trickling.stream().anyMatch(path::contains)) {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, 0);
      OutputStream out = exchange.getResponseBody();
      try {
        for (byte b : bytes) {
          out.write(b);
          out.flush();
          TimeUnit.MILLISECONDS.sleep(100);
        }
      } catch (IOException e) {
        // the client hung up
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return;
    }
    respond(exchange, 200, "application/json", bytes);
  }

  private static void error(HttpExchange exchange, int status, String code, String retryAfter)
      throws IOException {
    if (retryAfter != null) {
      exchange.getResponseHeaders().set("Retry-After", retryAfter);
    }
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", "fake " + code);
    error.put(
        "innerError",
        Map.of("date", "2026-10-04T10:00:00", "request-id", UUID.randomUUID().toString()));
    respond(exchange, status, "application/json", JSON.writeValueAsBytes(Map.of("error", error)));
  }

  private static void respond(HttpExchange exchange, int status, String type, byte[] bytes)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> query = new LinkedHashMap<>();
    if (raw == null) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0) {
        query.put(
            URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
    }
    return query;
  }

  // --- certificate -----------------------------------------------------------------------------

  private static SSLContext serverContext() throws Exception {
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(keyStore(), STORE_PASSWORD.toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    return context;
  }

  /** One self-signed certificate for {@code 127.0.0.1} and {@code localhost}, made per JVM. */
  private static KeyStore keyStore() throws Exception {
    KeyStore loaded = keyStore;
    if (loaded != null) {
      return loaded;
    }
    synchronized (FakeGraphServer.class) {
      if (keyStore == null) {
        keyStore = generateKeyStore();
      }
      return keyStore;
    }
  }

  private static KeyStore generateKeyStore() throws Exception {
    Path file = Files.createTempFile("opaa-fake-graph-", ".p12");
    Files.delete(file);
    try {
      String keytool =
          System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
      Process process =
          new ProcessBuilder(
                  keytool,
                  "-genkeypair",
                  "-alias",
                  "fake-graph",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=127.0.0.1",
                  "-ext",
                  "SAN=ip:127.0.0.1,dns:localhost",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  file.toString(),
                  "-storepass",
                  STORE_PASSWORD,
                  "-keypass",
                  STORE_PASSWORD)
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool did not finish: " + output);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(file)) {
        store.load(in, STORE_PASSWORD.toCharArray());
      }
      return store;
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Override
  public void close() {
    graph.stop(0);
    download.stop(0);
    plainDownload.stop(0);
    executor.shutdownNow();
  }
}
