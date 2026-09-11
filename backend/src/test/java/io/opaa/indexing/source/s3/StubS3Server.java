package io.opaa.indexing.source.s3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal path-style S3 stand-in for the adapter's unit tests: buckets with objects in memory,
 * {@code ListObjectsV2} with prefix, page size and continuation tokens, {@code HeadBucket}, {@code
 * HeadObject}, {@code GetObject}, {@code PutObject}, {@code DeleteObject}, {@code ListBuckets}, and
 * a script of failures the next requests answer with, regardless of route. Every request is
 * recorded for assertions.
 */
public final class StubS3Server implements AutoCloseable {

  /**
   * One request as the server saw it; {@code target} is the request-line URI - absolute when the
   * client talks through a proxy.
   */
  public record Seen(
      String method, String path, String query, Map<String, String> headers, String target) {}

  private record Failure(String method, String pathPrefix, int status, String code) {
    boolean matches(String method, String path) {
      return (this.method == null || this.method.equals(method))
          && (pathPrefix == null || path.startsWith(pathPrefix));
    }
  }

  private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

  private final HttpServer server;
  private final Map<String, Map<String, byte[]>> buckets = new LinkedHashMap<>();
  private final Map<String, String> contentTypes = new LinkedHashMap<>();
  private final Set<String> chunkedKeys = new java.util.HashSet<>();
  private final Deque<Failure> scripted = new ArrayDeque<>();
  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private volatile boolean omitContinuationToken;

  public StubS3Server() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  public String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void addBucket(String bucket) {
    buckets.computeIfAbsent(bucket, b -> new LinkedHashMap<>());
  }

  public void putObject(String bucket, String key, byte[] bytes, String contentType) {
    addBucket(bucket);
    buckets.get(bucket).put(key, bytes);
    contentTypes.put(bucket + "/" + key, contentType);
  }

  /** Removes {@code key} behind the client's back, as another writer to the bucket would. */
  public void removeObject(String bucket, String key) {
    buckets.getOrDefault(bucket, Map.of()).remove(key);
    contentTypes.remove(bucket + "/" + key);
  }

  /** Serves {@code key} chunked, without a {@code Content-Length} the client could reject early. */
  void serveChunked(String bucket, String key) {
    chunkedKeys.add(bucket + "/" + key);
  }

  /** Truncated listings claim {@code IsTruncated=true} but carry no continuation token. */
  void omitContinuationToken() {
    omitContinuationToken = true;
  }

  /** The next {@code times} requests - whatever they are - answer {@code status}/{@code code}. */
  public void failNext(int status, String code, int times) {
    for (int i = 0; i < times; i++) {
      scripted.add(new Failure(null, null, status, code));
    }
  }

  /** The next request with {@code method} whose path starts with {@code pathPrefix} fails. */
  public void failNextMatching(String method, String pathPrefix, int status, String code) {
    scripted.add(new Failure(method, pathPrefix, status, code));
  }

  public List<Seen> seen() {
    return seen;
  }

  static String md5(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getRawQuery();
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
    seen.add(new Seen(method, path, query, headers, exchange.getRequestURI().toString()));
    try {
      Failure failure = null;
      for (Failure candidate : scripted) {
        if (candidate.matches(method, path)) {
          failure = candidate;
          scripted.remove(candidate);
          break;
        }
      }
      if (failure != null) {
        error(exchange, failure.status(), failure.code());
        return;
      }
      if (path.equals("/")) {
        listBuckets(exchange);
        return;
      }
      String[] parts = path.substring(1).split("/", 2);
      String bucket = parts[0];
      String key = parts.length > 1 ? parts[1] : null;
      Map<String, byte[]> objects = buckets.get(bucket);
      if (objects == null) {
        error(exchange, 404, "NoSuchBucket");
        return;
      }
      if (key == null || key.isEmpty()) {
        if (method.equals("HEAD")) {
          exchange.sendResponseHeaders(200, -1);
        } else {
          list(exchange, bucket, objects, parseQuery(query));
        }
        return;
      }
      if (method.equals("PUT")) {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (headers.getOrDefault("x-amz-content-sha256", "").startsWith("STREAMING")) {
          body = decodeAwsChunked(body);
        }
        objects.put(key, body);
        contentTypes.put(
            bucket + "/" + key, headers.getOrDefault("content-type", "application/octet-stream"));
        exchange.getResponseHeaders().set("ETag", "\"" + md5(body) + "\"");
        exchange.sendResponseHeaders(200, -1);
        return;
      }
      if (method.equals("DELETE")) {
        // S3 answers 204 whether or not the key held an object
        objects.remove(key);
        contentTypes.remove(bucket + "/" + key);
        exchange.sendResponseHeaders(204, -1);
        return;
      }
      byte[] bytes = objects.get(key);
      if (bytes == null) {
        error(exchange, 404, "NoSuchKey");
        return;
      }
      String contentType =
          contentTypes.getOrDefault(bucket + "/" + key, "application/octet-stream");
      exchange.getResponseHeaders().set("ETag", "\"" + md5(bytes) + "\"");
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange
          .getResponseHeaders()
          .set("Last-Modified", RFC_1123.format(Instant.EPOCH.atOffset(ZoneOffset.UTC)));
      exchange.getResponseHeaders().set("x-amz-storage-class", "STANDARD");
      if (method.equals("HEAD")) {
        exchange.getResponseHeaders().set("Content-Length", Long.toString(bytes.length));
        exchange.sendResponseHeaders(200, -1);
        return;
      }
      if (chunkedKeys.contains(bucket + "/" + key)) {
        exchange.sendResponseHeaders(200, 0);
      } else {
        exchange.sendResponseHeaders(200, bytes.length);
      }
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    } finally {
      exchange.close();
    }
  }

  /**
   * The payload of a SigV4-streaming upload, which the SDK sends over plain HTTP: {@code
   * <hex-size>;chunk-signature=<sig>\r\n<data>\r\n} repeated, closed by a zero-size chunk and
   * optional trailers. A real store decodes this itself.
   */
  static byte[] decodeAwsChunked(byte[] encoded) {
    java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream();
    int position = 0;
    while (position < encoded.length) {
      int lineEnd = indexOfCrLf(encoded, position);
      String header = new String(encoded, position, lineEnd - position, StandardCharsets.US_ASCII);
      int semicolon = header.indexOf(';');
      int size = Integer.parseInt(semicolon < 0 ? header : header.substring(0, semicolon), 16);
      if (size == 0) {
        break;
      }
      int dataStart = lineEnd + 2;
      decoded.write(encoded, dataStart, size);
      position = dataStart + size + 2;
    }
    return decoded.toByteArray();
  }

  private static int indexOfCrLf(byte[] bytes, int from) {
    for (int i = from; i + 1 < bytes.length; i++) {
      if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
        return i;
      }
    }
    throw new IllegalArgumentException("aws-chunked body without a chunk header line");
  }

  private void listBuckets(HttpExchange exchange) throws IOException {
    StringBuilder xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListAllMyBucketsResult"
                + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Owner><ID>stub</ID></Owner><Buckets>");
    for (String name : buckets.keySet()) {
      xml.append("<Bucket><Name>")
          .append(name)
          .append("</Name><CreationDate>2026-01-01T00:00:00.000Z</CreationDate></Bucket>");
    }
    xml.append("</Buckets></ListAllMyBucketsResult>");
    xml(exchange, 200, xml.toString());
  }

  private void list(
      HttpExchange exchange, String bucket, Map<String, byte[]> objects, Map<String, String> query)
      throws IOException {
    String prefix = query.getOrDefault("prefix", "");
    int maxKeys = Integer.parseInt(query.getOrDefault("max-keys", "1000"));
    int start =
        query.containsKey("continuation-token")
            ? Integer.parseInt(query.get("continuation-token").substring(1))
            : 0;
    List<String> keys =
        objects.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
    int end = Math.min(start + maxKeys, keys.size());
    boolean truncated = end < keys.size();
    StringBuilder xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult"
                + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
    xml.append("<Name>")
        .append(bucket)
        .append("</Name><Prefix>")
        .append(prefix)
        .append("</Prefix>");
    xml.append("<KeyCount>")
        .append(end - start)
        .append("</KeyCount><MaxKeys>")
        .append(maxKeys)
        .append("</MaxKeys>");
    xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
    if (truncated && !omitContinuationToken) {
      xml.append("<NextContinuationToken>t").append(end).append("</NextContinuationToken>");
    }
    for (String key : keys.subList(start, end)) {
      byte[] bytes = objects.get(key);
      xml.append("<Contents><Key>")
          .append(key)
          .append("</Key><LastModified>2026-09-01T10:00:00.000Z</LastModified><ETag>&quot;")
          .append(md5(bytes))
          .append("&quot;</ETag><Size>")
          .append(bytes.length)
          .append("</Size><StorageClass>")
          .append(key.startsWith("archiv/") ? "GLACIER" : "STANDARD")
          .append("</StorageClass></Contents>");
    }
    xml.append("</ListBucketResult>");
    xml(exchange, 200, xml.toString());
  }

  private void error(HttpExchange exchange, int status, String code) throws IOException {
    if (exchange.getRequestMethod().equals("HEAD")) {
      exchange.sendResponseHeaders(status, -1);
      return;
    }
    String body =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>"
            + code
            + "</Code><Message>stubbed "
            + code
            + " - with a token-like value tok-4711 that must never surface</Message><Resource>"
            + exchange.getRequestURI().getPath()
            + "</Resource><RequestId>req-1</RequestId></Error>";
    if (status == 301) {
      exchange.getResponseHeaders().set("x-amz-bucket-region", "eu-central-1");
    }
    xml(exchange, status, body);
  }

  private static void xml(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/xml");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> result = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return result;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      String name =
          URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value =
          eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(name, value);
    }
    return result;
  }

  /** Every key currently stored in {@code bucket}, for test bookkeeping. */
  public List<String> keys(String bucket) {
    return new ArrayList<>(buckets.getOrDefault(bucket, Map.of()).keySet());
  }
}
