package io.opaa.indexing.source.nextcloud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A WebDAV server that answers like Nextcloud for one technical user: principal lookup, {@code
 * PROPFIND} with depth 0 and 1, {@code GET}. A folder's ETag is a hash over its children's names
 * and ETags, so a change propagates to the root and a renamed folder keeps its own - as Nextcloud
 * does. File ids survive a {@link #move}. The login name and the user id differ on purpose.
 */
final class FakeNextcloudServer implements AutoCloseable {

  static final String LOGIN = "techniker";
  static final String USER_ID = "tech-uid";
  static final String APP_PASSWORD = "app-passwort-123";

  private record Node(boolean folder, byte[] bytes, String contentType, long fileId, String etag) {}

  private final HttpServer server;
  private final String contextPath;
  private final TreeMap<String, Node> nodes = new TreeMap<>();
  private final AtomicLong fileIds = new AtomicLong(100);
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final Set<String> unlistable = new HashSet<>();
  private final Set<String> unreadable = new HashSet<>();
  private final Set<String> unopenable = new HashSet<>();
  private final Set<String> vanishing = new HashSet<>();
  private volatile String foreignFileHref;
  private volatile boolean credentialsRejected;

  FakeNextcloudServer(String contextPath) throws IOException {
    this.contextPath = contextPath;
    nodes.put("", folderNode(fileIds.incrementAndGet()));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + contextPath;
  }

  String credentials() {
    return LOGIN + ":" + APP_PASSWORD;
  }

  synchronized FakeNextcloudServer put(String path, String text) {
    return put(path, text.getBytes(StandardCharsets.UTF_8), "text/plain");
  }

  /** Creates or replaces the file {@code path} ({@code a/b.txt}), creating its folders. */
  synchronized FakeNextcloudServer put(String path, byte[] bytes, String contentType) {
    ensureFolders(parent(path));
    Node existing = nodes.get(path);
    long fileId = existing == null ? fileIds.incrementAndGet() : existing.fileId();
    nodes.put(path, new Node(false, bytes, contentType, fileId, hash(bytes, fileId)));
    return this;
  }

  synchronized FakeNextcloudServer mkdir(String path) {
    ensureFolders(path);
    return this;
  }

  synchronized FakeNextcloudServer remove(String path) {
    nodes.keySet().removeIf(key -> key.equals(path) || key.startsWith(path + "/"));
    return this;
  }

  /** Renames or moves {@code from} with everything below it; every file keeps its id. */
  synchronized FakeNextcloudServer move(String from, String to) {
    ensureFolders(parent(to));
    Map<String, Node> moved = new TreeMap<>();
    nodes.forEach(
        (key, node) -> {
          if (key.equals(from) || key.startsWith(from + "/")) {
            moved.put(to + key.substring(from.length()), node);
          }
        });
    remove(from);
    nodes.putAll(moved);
    return this;
  }

  synchronized long fileId(String path) {
    return nodes.get(path).fileId();
  }

  /** From now on {@code PROPFIND} on {@code folder} answers {@code 403}. */
  FakeNextcloudServer denyListing(String folder) {
    unlistable.add(folder);
    return this;
  }

  /** From now on every file below {@code folder} answers {@code 403} to {@code GET}. */
  FakeNextcloudServer denyReading(String folder) {
    unreadable.add(folder);
    return this;
  }

  /** From now on {@code GET} of the file {@code path} answers {@code 503}, listing still works. */
  FakeNextcloudServer unopenable(String path) {
    unopenable.add(path);
    return this;
  }

  /**
   * From now on {@code folder} answers its own {@code PROPFIND} with {@code 404} while its parent
   * still lists it - a folder renamed in the middle of a run.
   */
  FakeNextcloudServer vanishOnVisit(String folder) {
    vanishing.add(folder);
    return this;
  }

  /** From now on every href the server answers for a file is {@code href} instead. */
  FakeNextcloudServer answerFileHrefsWith(String href) {
    foreignFileHref = href;
    return this;
  }

  /**
   * Sabre's own path encoding, deliberately not the connector's: letters, digits and {@code _ - . ~
   * ( ) / : @} stay raw, every other UTF-8 byte becomes {@code %XX}.
   */
  static String sabreEncode(String path) {
    StringBuilder encoded = new StringBuilder();
    for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || "_-.~()/:@".indexOf(c) >= 0) {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format("%02X", b & 0xff));
      }
    }
    return encoded.toString();
  }

  /** {@link URLDecoder} turns {@code +} into a space; a path keeps it. */
  private static String plusSafe(String raw) {
    return raw.replace("+", "%2B");
  }

  FakeNextcloudServer rejectCredentials() {
    credentialsRejected = true;
    return this;
  }

  /** Every request so far, e.g. {@code PROPFIND 1 Projekte/Akten} or {@code GET a.txt}. */
  List<String> requests() {
    return List.copyOf(requests);
  }

  FakeNextcloudServer clearRequests() {
    requests.clear();
    return this;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getRequestBody().readAllBytes();
      String expected =
          "Basic "
              + Base64.getEncoder().encodeToString(credentials().getBytes(StandardCharsets.UTF_8));
      if (credentialsRejected
          || !expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        requests.add(exchange.getRequestMethod() + " (abgelehnt)");
        respond(exchange, 401, "");
        return;
      }
      String rawPath = exchange.getRequestURI().getRawPath();
      String davRoot = contextPath + "/remote.php/dav/";
      String filesRoot = davRoot + "files/" + USER_ID;
      if (exchange.getRequestMethod().equals("PROPFIND") && rawPath.equals(davRoot)) {
        requests.add("PROPFIND principal");
        respond(exchange, 207, principal());
        return;
      }
      if (!rawPath.startsWith(filesRoot)) {
        respond(exchange, 404, "");
        return;
      }
      String path =
          trim(
              URLDecoder.decode(
                  plusSafe(rawPath.substring(filesRoot.length())), StandardCharsets.UTF_8));
      if (exchange.getRequestMethod().equals("PROPFIND")) {
        String depth = exchange.getRequestHeaders().getFirst("Depth");
        requests.add("PROPFIND " + depth + " " + path);
        propfind(exchange, filesRoot, path, "0".equals(depth) ? 0 : 1);
      } else if (exchange.getRequestMethod().equals("GET")) {
        requests.add("GET " + path);
        get(exchange, path);
      } else {
        respond(exchange, 405, "");
      }
    }
  }

  private synchronized void propfind(
      HttpExchange exchange, String filesRoot, String path, int depth) throws IOException {
    Node node = nodes.get(path);
    if (node == null) {
      respond(exchange, 404, "");
      return;
    }
    if (node.folder() && vanishing.contains(path)) {
      respond(exchange, 404, "");
      return;
    }
    if (node.folder() && unlistable.contains(path)) {
      respond(exchange, 403, "");
      return;
    }
    StringBuilder xml =
        new StringBuilder(
            "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\""
                + " xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.org/ns\">");
    xml.append(response(filesRoot, path, node));
    if (depth == 1 && node.folder()) {
      for (String child : children(path)) {
        xml.append(response(filesRoot, child, nodes.get(child)));
      }
    }
    xml.append("</d:multistatus>");
    respond(exchange, 207, xml.toString());
  }

  private synchronized void get(HttpExchange exchange, String path) throws IOException {
    Node node = nodes.get(path);
    if (node == null || node.folder()) {
      respond(exchange, 404, "");
      return;
    }
    for (String folder : unreadable) {
      if (path.startsWith(folder + "/") || folder.isEmpty()) {
        respond(exchange, 403, "");
        return;
      }
    }
    if (unopenable.contains(path)) {
      respond(exchange, 503, "");
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", node.contentType());
    exchange.sendResponseHeaders(200, node.bytes().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(node.bytes());
    }
  }

  private String response(String filesRoot, String path, Node node) {
    String href = filesRoot + "/" + sabreEncode(path) + (node.folder() ? "/" : "");
    if (path.isEmpty()) {
      href = filesRoot + "/";
    }
    if (!node.folder() && foreignFileHref != null) {
      href = foreignFileHref;
    }
    StringBuilder xml = new StringBuilder("<d:response><d:href>").append(href);
    xml.append("</d:href><d:propstat><d:prop>");
    xml.append(
        node.folder() ? "<d:resourcetype><d:collection/></d:resourcetype>" : "<d:resourcetype/>");
    xml.append("<d:getetag>&quot;").append(etag(path)).append("&quot;</d:getetag>");
    xml.append("<oc:fileid>").append(node.fileId()).append("</oc:fileid>");
    xml.append("<nc:mount-type></nc:mount-type>");
    if (!node.folder()) {
      xml.append("<d:getcontentlength>")
          .append(node.bytes().length)
          .append("</d:getcontentlength>");
      xml.append("<d:getcontenttype>").append(node.contentType()).append("</d:getcontenttype>");
    }
    xml.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>");
    if (node.folder()) {
      xml.append(
          "<d:propstat><d:prop><d:getcontentlength/><d:getcontenttype/></d:prop>"
              + "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>");
    }
    return xml.append("</d:response>").toString();
  }

  /** A file's own ETag; a folder's hashes its children's names and ETags. */
  private String etag(String path) {
    Node node = nodes.get(path);
    if (!node.folder()) {
      return node.etag();
    }
    StringBuilder children = new StringBuilder("folder");
    for (String child : children(path)) {
      children
          .append('|')
          .append(child.substring(child.lastIndexOf('/') + 1))
          .append('=')
          .append(etag(child));
    }
    return hash(children.toString().getBytes(StandardCharsets.UTF_8), 0);
  }

  private List<String> children(String folder) {
    List<String> children = new ArrayList<>();
    String prefix = folder.isEmpty() ? "" : folder + "/";
    for (String key : nodes.keySet()) {
      if (!key.isEmpty() && key.startsWith(prefix) && key.indexOf('/', prefix.length()) < 0) {
        children.add(key);
      }
    }
    return children;
  }

  private void ensureFolders(String path) {
    if (path.isEmpty()) {
      return;
    }
    ensureFolders(parent(path));
    nodes.putIfAbsent(path, folderNode(fileIds.incrementAndGet()));
  }

  private static Node folderNode(long fileId) {
    return new Node(true, null, null, fileId, null);
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static String trim(String path) {
    String trimmed = path;
    while (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private String principal() {
    return "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>"
        + contextPath
        + "/remote.php/dav/</d:href><d:propstat><d:prop><d:current-user-principal><d:href>"
        + contextPath
        + "/remote.php/dav/principals/users/"
        + USER_ID
        + "/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status>"
        + "</d:propstat></d:response></d:multistatus>";
  }

  private static String hash(byte[] bytes, long salt) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(Long.toString(salt).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest(bytes), 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
