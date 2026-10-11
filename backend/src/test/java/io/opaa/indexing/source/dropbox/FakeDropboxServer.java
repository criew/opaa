package io.opaa.indexing.source.dropbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Dropbox API v2 on loopback, held in memory: the RPC routes and the content routes the
 * connector uses, with Dropbox's error forms ({@code 409} with {@code error_summary}, {@code 401}
 * for a refused token, {@code 429} with {@code Retry-After}). Dropbox offers no emulator; this
 * double is what the tests trust about Dropbox. A cursor names its folder and position in the
 * change log, so it outlives the run that received it. The team space is a second tree, chosen by
 * {@code Dropbox-API-Path-Root}.
 */
final class FakeDropboxServer implements AutoCloseable {

  static final String TOKEN = "sl.fake-dropbox-token";
  static final String ACCOUNT = "ablage@example.org";
  static final String TEAM_ROOT = "team-root-1";
  static final String HOME = "home-1";

  /** One file or folder below a root. */
  static final class Item {
    final String id;
    String pathDisplay;
    boolean folder;
    boolean paper;
    boolean notDownloadable;
    byte[] content = new byte[0];
    int rev = 1;

    Item(String id, String pathDisplay, boolean folder) {
      this.id = id;
      this.pathDisplay = pathDisplay;
      this.folder = folder;
    }

    String pathLower() {
      return pathDisplay.toLowerCase(Locale.ROOT);
    }
  }

  /** One entry of the change log: the path and whether it was deleted. */
  private record Event(int seq, String root, String pathLower, boolean deleted) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Map<String, Map<String, Item>> roots = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> deletedFiles = new ConcurrentHashMap<>();
  private final List<Event> events = new CopyOnWriteArrayList<>();
  private final AtomicInteger seq = new AtomicInteger();
  private final AtomicInteger ids = new AtomicInteger();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> tokens = new CopyOnWriteArrayList<>();
  private final Set<String> deniedListing = ConcurrentHashMap.newKeySet();
  private final Set<String> deniedReading = ConcurrentHashMap.newKeySet();
  private final AtomicInteger throttles = new AtomicInteger();
  private final AtomicInteger brokenDownloads = new AtomicInteger();
  private volatile String acceptedToken = TOKEN;
  private volatile String alsoAcceptedToken = TOKEN;
  private volatile boolean cursorsExpired;
  private volatile boolean team;

  FakeDropboxServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    roots.put(HOME, new ConcurrentHashMap<>());
    roots.put(TEAM_ROOT, new ConcurrentHashMap<>());
    server.createContext("/2/", this::handle);
    server.start();
  }

  URI base() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  List<String> requests() {
    return requests;
  }

  List<String> tokens() {
    return tokens;
  }

  // --- arranging -------------------------------------------------------------------------------

  /** The account belongs to a team with a team space. */
  void withTeamSpace() {
    team = true;
  }

  Item folder(String path) {
    return folder(HOME, path);
  }

  Item folder(String root, String path) {
    Map<String, Item> tree = roots.get(root);
    Item existing = tree.get(path.toLowerCase(Locale.ROOT));
    if (existing != null) {
      return existing;
    }
    ensureParents(root, path);
    Item folder = new Item("id:d" + ids.incrementAndGet(), path, true);
    tree.put(folder.pathLower(), folder);
    return folder;
  }

  Item file(String path, String text) {
    return file(HOME, path, text.getBytes(StandardCharsets.UTF_8));
  }

  Item file(String root, String path, byte[] bytes) {
    Map<String, Item> tree = roots.get(root);
    Item existing = tree.get(path.toLowerCase(Locale.ROOT));
    if (existing != null) {
      existing.content = bytes;
      existing.rev++;
      return existing;
    }
    ensureParents(root, path);
    Item file = new Item("id:f" + ids.incrementAndGet(), path, false);
    file.content = bytes;
    tree.put(file.pathLower(), file);
    return file;
  }

  Item get(String path) {
    return roots.get(HOME).get(path.toLowerCase(Locale.ROOT));
  }

  /** Deletes {@code path} and everything below it, without noting it in the change log. */
  void remove(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    Map<String, Item> tree = roots.get(HOME);
    for (String key : List.copyOf(tree.keySet())) {
      if (key.equals(lower) || key.startsWith(lower + "/")) {
        Item removed = tree.remove(key);
        if (removed != null && !removed.folder) {
          deletedFiles.computeIfAbsent(HOME, root -> ConcurrentHashMap.newKeySet()).add(key);
        }
      }
    }
  }

  /** Notes in the change log that {@code path} changed - or was deleted, if it no longer exists. */
  void changed(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    events.add(new Event(seq.incrementAndGet(), HOME, lower, !roots.get(HOME).containsKey(lower)));
  }

  /** Moves the file or folder {@code from} to {@code to}, noting both paths. */
  void move(String from, String to) {
    Map<String, Item> tree = roots.get(HOME);
    String fromLower = from.toLowerCase(Locale.ROOT);
    List<Item> moved = new ArrayList<>();
    for (String key : List.copyOf(tree.keySet())) {
      if (key.equals(fromLower) || key.startsWith(fromLower + "/")) {
        Item item = tree.remove(key);
        if (!item.folder) {
          // a moved file keeps its revisions under the old path, as Dropbox reports them
          deletedFiles.computeIfAbsent(HOME, root -> ConcurrentHashMap.newKeySet()).add(key);
        }
        item.pathDisplay = to + item.pathDisplay.substring(from.length());
        moved.add(item);
      }
    }
    ensureParents(HOME, to);
    for (Item item : moved) {
      tree.put(item.pathLower(), item);
    }
    events.add(new Event(seq.incrementAndGet(), HOME, fromLower, true));
    for (Item item : moved) {
      events.add(new Event(seq.incrementAndGet(), HOME, item.pathLower(), false));
    }
  }

  void denyListing(String folder) {
    deniedListing.add(folder.toLowerCase(Locale.ROOT));
  }

  void denyReading(String folder) {
    deniedReading.add(folder.toLowerCase(Locale.ROOT));
  }

  /** Every token is refused from now on. */
  void rejectToken() {
    acceptedToken = "none";
    alsoAcceptedToken = "none";
  }

  /** {@code token} is taken besides the usual one. */
  void alsoAccept(String token) {
    alsoAcceptedToken = token;
  }

  /** Only {@code token} is taken from now on. */
  void acceptOnly(String token) {
    acceptedToken = token;
    alsoAcceptedToken = token;
  }

  void expireCursors() {
    cursorsExpired = true;
  }

  /** The next {@code times} requests are answered {@code 429} with {@code Retry-After: 1}. */
  void throttleNext(int times) {
    throttles.set(times);
  }

  /** The next download breaks off after its first bytes, as a dropped connection does. */
  void breakNextDownload() {
    brokenDownloads.set(1);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void ensureParents(String root, String path) {
    int slash = path.lastIndexOf('/');
    if (slash > 0) {
      folder(root, path.substring(0, slash));
    }
  }

  // --- serving ---------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String route = exchange.getRequestURI().getPath().substring("/2/".length());
      requests.add(route);
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      String token =
          authorization != null && authorization.startsWith("Bearer ")
              ? authorization.substring("Bearer ".length())
              : null;
      tokens.add(String.valueOf(token));
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      if (throttles.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
        exchange.getResponseHeaders().add("Retry-After", "1");
        json(exchange, 429, Map.of("error_summary", "too_many_requests/..."));
        return;
      }
      if (token == null || !(token.equals(acceptedToken) || token.equals(alsoAcceptedToken))) {
        json(
            exchange,
            401,
            Map.of(
                "error_summary",
                "invalid_access_token/...",
                "error",
                Map.of(".tag", "invalid_access_token")));
        return;
      }
      String root = rootOf(exchange);
      if (root == null) {
        json(exchange, 400, Map.of("error_summary", "invalid_root/..."));
        return;
      }
      JsonNode args =
          route.startsWith("files/download") || route.startsWith("files/export")
              ? JSON.readTree(exchange.getRequestHeaders().getFirst("Dropbox-API-Arg"))
              : JSON.readTree(requestBody.length == 0 ? "null".getBytes() : requestBody);
      switch (route) {
        case "users/get_current_account" -> account(exchange);
        case "files/get_metadata" -> metadata(exchange, root, text(args, "path"));
        case "files/list_folder" ->
            listFolder(
                exchange,
                root,
                text(args, "path"),
                args.get("recursive") != null && args.get("recursive").asBoolean(),
                args.get("limit") == null ? 2000 : args.get("limit").asInt());
        case "files/list_folder/continue" -> continueListing(exchange, text(args, "cursor"));
        case "files/list_folder/get_latest_cursor" ->
            latestCursor(
                exchange,
                root,
                text(args, "path"),
                args.get("limit") == null ? 2000 : args.get("limit").asInt());
        case "files/list_revisions" -> revisions(exchange, root, text(args, "path"));
        case "files/download" -> download(exchange, root, text(args, "path"), false);
        case "files/export" -> download(exchange, root, text(args, "path"), true);
        default -> json(exchange, 400, Map.of("error_summary", "unknown_route/..."));
      }
    }
  }

  private String rootOf(HttpExchange exchange) throws IOException {
    String header = exchange.getRequestHeaders().getFirst("Dropbox-API-Path-Root");
    if (header == null) {
      return HOME;
    }
    JsonNode root = JSON.readTree(header);
    String id = text(root, "root");
    return team && TEAM_ROOT.equals(id) ? TEAM_ROOT : null;
  }

  private void account(HttpExchange exchange) throws IOException {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put(".tag", team ? "team" : "user");
    info.put("root_namespace_id", team ? TEAM_ROOT : HOME);
    info.put("home_namespace_id", HOME);
    json(exchange, 200, Map.of("email", ACCOUNT, "root_info", info));
  }

  private void metadata(HttpExchange exchange, String root, String path) throws IOException {
    Item item = find(root, path);
    if (item == null) {
      notFound(exchange, "path");
      return;
    }
    if (item.folder && deniedListing.contains(item.pathLower())) {
      json(exchange, 409, Map.of("error_summary", "path/restricted_content/..."));
      return;
    }
    json(exchange, 200, meta(item));
  }

  private void listFolder(
      HttpExchange exchange, String root, String path, boolean recursive, int limit)
      throws IOException {
    String folder = lower(path);
    if (!folder.isEmpty()) {
      Item item = roots.get(root).get(folder);
      if (item == null || !item.folder) {
        notFound(exchange, "path");
        return;
      }
    }
    if (denied(deniedListing, folder)) {
      json(exchange, 409, Map.of("error_summary", "path/restricted_content/..."));
      return;
    }
    page(exchange, cursor("l", root, folder, 0, limit) + (recursive ? "" : "|flat"));
  }

  /** As Dropbox answers: a missing folder is {@code path/not_found}, a denied one restricted. */
  private void latestCursor(HttpExchange exchange, String root, String path, int limit)
      throws IOException {
    String folder = lower(path);
    if (!folder.isEmpty()) {
      Item item = roots.get(root).get(folder);
      if (item == null || !item.folder) {
        notFound(exchange, "path");
        return;
      }
    }
    if (denied(deniedListing, folder)) {
      json(exchange, 409, Map.of("error_summary", "path/restricted_content/..."));
      return;
    }
    json(exchange, 200, Map.of("cursor", cursor("c", root, folder, seq.get(), limit)));
  }

  private void continueListing(HttpExchange exchange, String cursor) throws IOException {
    String[] parts =
        decode(cursor.endsWith("|flat") ? cursor.substring(0, cursor.length() - 5) : cursor);
    if (parts == null) {
      json(exchange, 400, Map.of("error_summary", "bad_cursor/..."));
      return;
    }
    if (parts[0].equals("c")) {
      if (cursorsExpired) {
        json(exchange, 409, Map.of("error_summary", "reset/...", "error", Map.of(".tag", "reset")));
        return;
      }
      changes(exchange, parts);
      return;
    }
    page(exchange, cursor);
  }

  /** One page of a listing from the position the cursor names. */
  private void page(HttpExchange exchange, String cursor) throws IOException {
    boolean flat = cursor.endsWith("|flat");
    String[] parts = decode(flat ? cursor.substring(0, cursor.length() - 5) : cursor);
    String root = parts[1];
    String folder = parts[2];
    int offset = Integer.parseInt(parts[3]);
    int limit = Integer.parseInt(parts[4]);
    if (denied(deniedListing, folder)) {
      json(exchange, 409, Map.of("error_summary", "path/restricted_content/..."));
      return;
    }
    List<Item> below = new ArrayList<>();
    for (Item item : new TreeMap<>(roots.get(root)).values()) {
      String lower = item.pathLower();
      boolean inside = folder.isEmpty() || lower.startsWith(folder + "/");
      if (!inside) {
        continue;
      }
      String rest = lower.substring(folder.length() + 1);
      if (flat && rest.contains("/")) {
        continue;
      }
      below.add(item);
    }
    int end = Math.min(below.size(), offset + limit);
    List<Map<String, Object>> entries = new ArrayList<>();
    for (Item item : below.subList(Math.min(offset, below.size()), end)) {
      entries.add(meta(item));
    }
    boolean more = end < below.size();
    String next =
        more
            ? cursor("l", root, folder, end, limit) + (flat ? "|flat" : "")
            : cursor("c", root, folder, seq.get(), limit);
    json(exchange, 200, Map.of("entries", entries, "cursor", next, "has_more", more));
  }

  /** One page of the change log after the position the cursor names, below its folder. */
  private void changes(HttpExchange exchange, String[] parts) throws IOException {
    String root = parts[1];
    String folder = parts[2];
    int after = Integer.parseInt(parts[3]);
    int limit = Integer.parseInt(parts[4]);
    List<Map<String, Object>> entries = new ArrayList<>();
    int last = after;
    boolean more = false;
    for (Event event : events) {
      if (event.seq() <= after || !event.root().equals(root)) {
        continue;
      }
      if (!folder.isEmpty() && !event.pathLower().startsWith(folder + "/")) {
        last = event.seq();
        continue;
      }
      if (entries.size() == limit) {
        more = true;
        break;
      }
      Item item = roots.get(root).get(event.pathLower());
      if (event.deleted() || item == null) {
        Map<String, Object> deleted = new LinkedHashMap<>();
        deleted.put(".tag", "deleted");
        deleted.put("name", event.pathLower().substring(event.pathLower().lastIndexOf('/') + 1));
        deleted.put("path_lower", event.pathLower());
        deleted.put("path_display", event.pathLower());
        entries.add(deleted);
      } else {
        entries.add(meta(item));
      }
      last = event.seq();
    }
    json(
        exchange,
        200,
        Map.of(
            "entries",
            entries,
            "cursor",
            cursor("c", root, folder, last, limit),
            "has_more",
            more));
  }

  private void revisions(HttpExchange exchange, String root, String path) throws IOException {
    String lower = lower(path);
    Item item = roots.get(root).get(lower);
    if (item != null && item.folder) {
      json(exchange, 409, Map.of("error_summary", "path/not_file/..."));
      return;
    }
    if (item == null && !deletedFiles.getOrDefault(root, Set.of()).contains(lower)) {
      notFound(exchange, "path");
      return;
    }
    json(exchange, 200, Map.of("is_deleted", item == null, "entries", List.of()));
  }

  private void download(HttpExchange exchange, String root, String path, boolean export)
      throws IOException {
    Item item = find(root, path);
    if (item == null || item.folder) {
      notFound(exchange, "path");
      return;
    }
    if (denied(deniedReading, item.pathLower())) {
      json(exchange, 409, Map.of("error_summary", "path/restricted_content/..."));
      return;
    }
    if (!export && (item.paper || item.notDownloadable)) {
      json(exchange, 409, Map.of("error_summary", "unsupported_file/..."));
      return;
    }
    // as Dropbox does: a header carries ASCII only
    exchange.getResponseHeaders().add("Dropbox-API-Result", DropboxApi.asciiJson(meta(item)));
    if (brokenDownloads.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
      exchange.sendResponseHeaders(200, item.content.length + 100L);
      exchange.getResponseBody().write(item.content);
      exchange.getResponseBody().flush();
      return;
    }
    exchange.sendResponseHeaders(200, item.content.length == 0 ? -1 : item.content.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(item.content);
    }
  }

  private Map<String, Object> meta(Item item) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put(".tag", item.folder ? "folder" : "file");
    meta.put("name", item.pathDisplay.substring(item.pathDisplay.lastIndexOf('/') + 1));
    meta.put("id", item.id);
    meta.put("path_lower", item.pathLower());
    meta.put("path_display", item.pathDisplay);
    if (!item.folder) {
      meta.put("rev", "0" + item.rev);
      meta.put("size", item.content.length);
      if (item.paper) {
        meta.put("is_downloadable", false);
        meta.put(
            "export_info",
            Map.of("export_as", "html", "export_options", List.of("markdown", "html")));
      } else if (item.notDownloadable) {
        meta.put("is_downloadable", false);
      } else {
        meta.put("is_downloadable", true);
        meta.put("content_hash", hash(item.content));
      }
    }
    return meta;
  }

  private Item find(String root, String path) {
    if (path == null) {
      return null;
    }
    if (path.startsWith("id:")) {
      return roots.get(root).values().stream()
          .filter(item -> item.id.equals(path))
          .findFirst()
          .orElse(null);
    }
    return roots.get(root).get(lower(path));
  }

  private static boolean denied(Set<String> folders, String path) {
    for (String folder : folders) {
      if (path.equals(folder) || path.startsWith(folder + "/")) {
        return true;
      }
    }
    return false;
  }

  private static void notFound(HttpExchange exchange, String field) throws IOException {
    json(exchange, 409, Map.of("error_summary", field + "/not_found/..."));
  }

  private static String cursor(String mode, String root, String folder, int position, int limit) {
    String plain = mode + "|" + root + "|" + folder + "|" + position + "|" + limit;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  private static String[] decode(String cursor) {
    try {
      String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = plain.split("\\|", -1);
      return parts.length == 5 ? parts : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String lower(String path) {
    return path == null ? "" : path.toLowerCase(Locale.ROOT);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String hash(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void json(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
