package io.opaa.indexing.source.googledrive;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Drive API v3 on loopback, held in memory: the endpoints and JSON forms the connector uses
 * ({@code drives}, {@code files} with the query forms it sends, {@code alt=media}, {@code export},
 * {@code changes}). Google offers no emulator; this double is what the tests trust about Drive, so
 * its forms follow the reference (error body with {@code errors[].reason}).
 */
final class FakeDriveServer implements AutoCloseable {

  static final String TOKEN = "ya29.fake-drive-token";
  static final String ROOT_ID = "myroot";

  /** One file or folder. */
  static final class Item {
    final String id;
    String name;
    String mimeType;
    String parent;
    String driveId;
    boolean trashed;
    boolean canDownload = true;
    boolean sharedWithMe;
    boolean exportTooLarge;
    byte[] content = new byte[0];
    Instant modifiedTime = Instant.parse("2026-10-01T10:00:00Z");

    Item(String id) {
      this.id = id;
    }
  }

  /** A failure the next matching requests answer with. */
  private record Failure(String pathPart, int status, String reason, int[] remaining) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Map<String, String> drives = new LinkedHashMap<>();
  private final Map<String, Item> items = new ConcurrentHashMap<>();
  private final List<String[]> changeLog = new CopyOnWriteArrayList<>();
  private final List<Failure> failures = new CopyOnWriteArrayList<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile String acceptedToken = TOKEN;
  private volatile boolean cursorsExpired;

  FakeDriveServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Item root = new Item(ROOT_ID);
    root.name = "Meine Ablage";
    root.mimeType = DriveFile.FOLDER;
    items.put(ROOT_ID, root);
    server.createContext("/drive/v3/", this::handle);
    server.start();
  }

  URI base() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  List<String> requests() {
    return requests;
  }

  // --- arranging -------------------------------------------------------------------------------

  void addDrive(String id, String name) {
    drives.put(id, name);
  }

  Item folder(String id, String name, String parent, String driveId) {
    Item folder = item(id, name, DriveFile.FOLDER, parent, driveId);
    return folder;
  }

  Item file(String id, String name, String mimeType, String parent, String driveId, String text) {
    Item file = item(id, name, mimeType, parent, driveId);
    file.content = text.getBytes(StandardCharsets.UTF_8);
    return file;
  }

  Item file(String id, String name, String mimeType, String parent, String driveId, byte[] bytes) {
    Item file = item(id, name, mimeType, parent, driveId);
    file.content = bytes;
    return file;
  }

  Item get(String id) {
    return items.get(id);
  }

  void remove(String id) {
    items.remove(id);
  }

  /** Notes a change of {@code id} in the stream of {@code driveId} ({@code null}: the user's). */
  void changed(String id, String driveId) {
    changeLog.add(new String[] {driveId == null ? "user" : driveId, id, "false"});
  }

  /** Notes a removal ({@code removed=true}) of {@code id}. */
  void removed(String id, String driveId) {
    changeLog.add(new String[] {driveId == null ? "user" : driveId, id, "true"});
  }

  void failNext(String pathPart, int status, String reason, int times) {
    failures.add(new Failure(pathPart, status, reason, new int[] {times}));
  }

  void rejectToken() {
    acceptedToken = "none";
  }

  void expireCursors() {
    cursorsExpired = true;
  }

  private Item item(String id, String name, String mimeType, String parent, String driveId) {
    Item item = new Item(id);
    item.name = name;
    item.mimeType = mimeType;
    item.parent = parent;
    item.driveId = driveId;
    item.modifiedTime = Instant.now();
    items.put(id, item);
    return item;
  }

  // --- serving ---------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath().substring("/drive/v3/".length());
    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
    requests.add(path + (query.containsKey("alt") ? "?alt=" + query.get("alt") : ""));
    try {
      if (!("Bearer " + acceptedToken)
          .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        error(exchange, 401, "authError");
        return;
      }
      for (Failure failure : failures) {
        if (path.contains(failure.pathPart()) && failure.remaining()[0] > 0) {
          failure.remaining()[0]--;
          error(exchange, failure.status(), failure.reason());
          return;
        }
      }
      route(exchange, path, query);
    } finally {
      exchange.close();
    }
  }

  private void route(HttpExchange exchange, String path, Map<String, String> query)
      throws IOException {
    if (path.equals("drives")) {
      List<Map<String, Object>> list = new ArrayList<>();
      drives.forEach((id, name) -> list.add(Map.of("id", id, "name", name)));
      json(exchange, Map.of("drives", list));
      return;
    }
    if (path.startsWith("drives/")) {
      String id = path.substring("drives/".length());
      if (!drives.containsKey(id)) {
        error(exchange, 404, "notFound");
        return;
      }
      json(exchange, Map.of("id", id, "name", drives.get(id)));
      return;
    }
    if (path.equals("changes/startPageToken")) {
      json(exchange, Map.of("startPageToken", Integer.toString(changeLog.size())));
      return;
    }
    if (path.equals("changes")) {
      changes(exchange, query);
      return;
    }
    if (path.equals("files")) {
      list(exchange, query);
      return;
    }
    Matcher export = Pattern.compile("files/([^/]+)/export").matcher(path);
    if (export.matches()) {
      Item item = items.get(export.group(1));
      if (item == null) {
        error(exchange, 404, "notFound");
      } else if (item.exportTooLarge && !"text/plain".equals(query.get("mimeType"))) {
        error(exchange, 403, "exportSizeLimitExceeded");
      } else {
        bytes(exchange, item.content);
      }
      return;
    }
    if (path.startsWith("files/")) {
      String id = path.substring("files/".length());
      Item item = items.get(id.equals("root") ? ROOT_ID : id);
      if (item == null) {
        error(exchange, 404, "notFound");
      } else if ("media".equals(query.get("alt"))) {
        if (!item.canDownload) {
          error(exchange, 403, "cannotDownloadFile");
        } else {
          bytes(exchange, item.content);
        }
      } else {
        json(exchange, resource(item));
      }
      return;
    }
    error(exchange, 404, "notFound");
  }

  private void list(HttpExchange exchange, Map<String, String> query) throws IOException {
    String q = query.getOrDefault("q", "");
    List<Item> matching = new ArrayList<>();
    Matcher inParents = Pattern.compile("'([^']+)' in parents").matcher(q);
    String driveId = query.get("driveId");
    for (Item item : items.values().stream().sorted((a, b) -> a.id.compareTo(b.id)).toList()) {
      if (item.id.equals(ROOT_ID) || (q.contains("trashed = false") && item.trashed)) {
        continue;
      }
      boolean folder = DriveFile.FOLDER.equals(item.mimeType);
      if (q.contains("sharedWithMe")) {
        if (folder && item.sharedWithMe) {
          matching.add(item);
        }
        continue;
      }
      if (inParents.find(0)) {
        String parent = inParents.group(1);
        String effective = item.parent;
        if (parent.equals("root")) {
          parent = ROOT_ID;
        }
        if (parent.equals(effective)) {
          matching.add(item);
        }
        continue;
      }
      if (driveId != null && driveId.equals(item.driveId)) {
        if (q.contains("mimeType != ") && !folder || q.contains("mimeType = ") && folder) {
          matching.add(item);
        }
      }
    }
    page(exchange, query, matching.stream().map(this::resource).toList(), "files");
  }

  private void changes(HttpExchange exchange, Map<String, String> query) throws IOException {
    if (cursorsExpired) {
      error(exchange, 410, "invalid");
      return;
    }
    String stream = query.getOrDefault("driveId", "user");
    int start = Integer.parseInt(query.get("pageToken"));
    int size = Integer.parseInt(query.getOrDefault("pageSize", "1000"));
    List<Map<String, Object>> changes = new ArrayList<>();
    int index = start;
    for (; index < changeLog.size() && changes.size() < size; index++) {
      String[] logged = changeLog.get(index);
      if (!logged[0].equals(stream)) {
        continue;
      }
      Map<String, Object> change = new LinkedHashMap<>();
      change.put("changeType", "file");
      change.put("fileId", logged[1]);
      Item item = items.get(logged[1]);
      boolean removed = "true".equals(logged[2]) || item == null;
      change.put("removed", removed);
      if (!removed) {
        change.put("file", resource(item));
      }
      changes.add(change);
    }
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("changes", changes);
    boolean more = false;
    for (int rest = index; rest < changeLog.size(); rest++) {
      more |= changeLog.get(rest)[0].equals(stream);
    }
    if (more) {
      answer.put("nextPageToken", Integer.toString(index));
    } else {
      answer.put("newStartPageToken", Integer.toString(changeLog.size()));
    }
    json(exchange, answer);
  }

  private void page(
      HttpExchange exchange, Map<String, String> query, List<Map<String, Object>> all, String field)
      throws IOException {
    int size = Integer.parseInt(query.getOrDefault("pageSize", "100"));
    int start = query.containsKey("pageToken") ? Integer.parseInt(query.get("pageToken")) : 0;
    int end = Math.min(all.size(), start + size);
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put(field, all.subList(start, end));
    if (end < all.size()) {
      answer.put("nextPageToken", Integer.toString(end));
    }
    json(exchange, answer);
  }

  private Map<String, Object> resource(Item item) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", item.id);
    json.put("name", item.name);
    json.put("mimeType", item.mimeType);
    if (item.parent != null) {
      json.put("parents", List.of(item.parent));
    }
    if (item.driveId != null) {
      json.put("driveId", item.driveId);
    }
    json.put("trashed", item.trashed);
    json.put("modifiedTime", item.modifiedTime.toString());
    json.put("capabilities", Map.of("canDownload", item.canDownload));
    if (item.mimeType != null && !item.mimeType.startsWith("application/vnd.google-apps.")) {
      json.put("size", Long.toString(item.content.length));
      json.put("md5Checksum", md5(item.content));
    }
    return json;
  }

  private static void json(HttpExchange exchange, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void bytes(HttpExchange exchange, byte[] bytes) throws IOException {
    exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  private static void error(HttpExchange exchange, int status, String reason) throws IOException {
    byte[] bytes =
        JSON.writeValueAsBytes(
            Map.of(
                "error",
                Map.of(
                    "code",
                    status,
                    "message",
                    "fake " + reason,
                    "errors",
                    List.of(Map.of("domain", "global", "reason", reason, "message", reason)))));
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> query = new LinkedHashMap<>();
    if (raw == null) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        query.put(
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return query;
  }

  private static String md5(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
