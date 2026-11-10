package io.opaa.query.sandbox;

import io.opaa.compliance.DataFlow;
import io.opaa.query.sandbox.SandboxSession.OutputEntry;
import io.opaa.query.sandbox.SandboxSession.OutputFile;
import io.opaa.query.sandbox.SandboxSession.ScriptRun;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link Sandboxes} on an OpenSandbox server (ADR-0073): each session is one sandbox, created with
 * its own random execd access token, reached only through the server's proxy route with that token
 * and deleted on {@link SandboxSession#close()}; the server's TTL removes one this process could
 * not delete. The sandbox gets no network: in Docker its network has no gateway, on Kubernetes a
 * NetworkPolicy of the Helm chart closes its egress.
 */
@DataFlow("code-sandbox")
@Component
@ConditionalOnProperty(name = "opaa.sandbox.enabled", havingValue = "true")
class OpenSandboxClient implements Sandboxes {

  private static final Logger log = LoggerFactory.getLogger(OpenSandboxClient.class);

  static final String API_KEY_HEADER = "OPEN-SANDBOX-API-KEY";
  static final String EXECD_TOKEN_HEADER = "X-EXECD-ACCESS-TOKEN";
  static final int EXECD_PORT = 44772;
  static final String SCRIPT_PATH = "/workspace/main.py";

  /** Bounds of what comes back from the sandbox; anything beyond is dropped, never buffered. */
  static final int MAX_FILES = MAX_OUTPUT_FILES;

  static final long MAX_FILE_BYTES = MAX_OUTPUT_FILE_BYTES;
  static final int MAX_STREAM_CHARS = 64 * 1024;

  /** Longest event line and longest directory listing read from execd; more fails the run. */
  static final int MAX_EVENT_CHARS = 256 * 1024;

  static final int MAX_LISTING_BYTES = 256 * 1024;

  /** The most output files {@link SandboxSession#outputEntries()} names. */
  static final int MAX_LISTED = 50;

  /** The server refuses a TTL below a minute. */
  private static final long MIN_TTL_SECONDS = 60;

  private static final int SANDBOX_UID = 1000;
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

  private final String baseUrl;
  private final String apiKey;
  private final String image;
  private final Duration maxLifetime;
  private final String cpu;
  private final String memory;
  private final ObjectMapper objectMapper;
  private final SecureRandom random = new SecureRandom();

  // The server is local infrastructure next to the backend, never reached through the proxy.
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .proxy(HttpClient.Builder.NO_PROXY)
          .build();

  OpenSandboxClient(SandboxProperties properties, ObjectMapper objectMapper) {
    String url = properties.baseUrl();
    this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    this.apiKey = properties.apiKey();
    this.image = properties.image();
    this.maxLifetime = properties.maxLifetime();
    this.cpu = properties.cpu();
    this.memory = properties.memory();
    this.objectMapper = objectMapper;
  }

  @PreDestroy
  void close() {
    httpClient.close();
  }

  @Override
  public SandboxSession open(Duration lifetime, Duration readyWithin) {
    long readyBy = System.nanoTime() + readyWithin.toNanos();
    String token = HexFormat.of().formatHex(randomBytes());
    long ttl = Math.max(MIN_TTL_SECONDS, Math.min(maxLifetime.toSeconds(), lifetime.toSeconds()));
    String sandboxId = create(token, ttl);
    Session session = new Session(sandboxId, token);
    try {
      awaitReady(session.execd, token, readyBy);
    } catch (RuntimeException e) {
      session.close();
      throw e;
    }
    return session;
  }

  /** One sandbox; its methods are called one after the other by the run that opened it. */
  private final class Session implements SandboxSession {

    private final String sandboxId;
    private final String token;
    private final String execd;
    private boolean closed;

    private Session(String sandboxId, String token) {
      this.sandboxId = sandboxId;
      this.token = token;
      this.execd = baseUrl + "/v1/sandboxes/" + sandboxId + "/proxy/" + EXECD_PORT;
    }

    @Override
    public String id() {
      return sandboxId;
    }

    @Override
    public synchronized ScriptRun run(String code, Duration timeout) {
      requireOpen();
      upload(execd, token, code);
      return execute(execd, token, timeout);
    }

    @Override
    public synchronized List<OutputEntry> outputEntries() {
      requireOpen();
      return entries(listOutput(execd, token));
    }

    @Override
    public synchronized List<OutputFile> collectOutput(
        Predicate<String> accepted, Duration within) {
      requireOpen();
      long until = System.nanoTime() + within.toNanos();
      return outputFiles(
          entries(listOutput(execd, token)),
          accepted,
          () -> System.nanoTime() - until >= 0,
          path -> download(execd, token, path));
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        delete(sandboxId);
      }
    }

    private void requireOpen() {
      if (closed) {
        throw new IllegalStateException("The sandbox session is closed");
      }
    }
  }

  private String create(String token, long ttlSeconds) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("image", Map.of("uri", image));
    body.put("entrypoint", List.of("tail", "-f", "/dev/null"));
    // The TTL removes a sandbox this process could not delete.
    body.put("timeout", ttlSeconds);
    body.put("resourceLimits", Map.of("cpu", cpu, "memory", memory));
    body.put("env", Map.of("EXECD_ACCESS_TOKEN", token));
    body.put("metadata", Map.of("purpose", "run_code"));
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/v1/sandboxes"))
                .header(API_KEY_HEADER, apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))),
            HttpResponse.BodyHandlers.ofString());
    requireSuccess(response, "create sandbox");
    String id = objectMapper.readTree(response.body()).path("id").asString();
    if (!id.matches("[A-Za-z0-9-]+")) {
      throw new SandboxException("create sandbox answered without a usable id");
    }
    return id;
  }

  /** Pings execd until it answers or {@code readyBy} (a {@link System#nanoTime()}) has passed. */
  private void awaitReady(String execd, String token, long readyBy) {
    while (true) {
      long left = readyBy - System.nanoTime();
      if (left <= 0) {
        throw new SandboxException("execd did not become ready in time");
      }
      Duration callTimeout =
          Duration.ofNanos(
              Math.max(Duration.ofMillis(100).toNanos(), Math.min(left, CALL_TIMEOUT.toNanos())));
      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(URI.create(execd + "/ping"))
                  .header(EXECD_TOKEN_HEADER, token)
                  .timeout(callTimeout)
                  .GET(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return;
      }
      sleep(Duration.ofMillis(250));
    }
  }

  private void upload(String execd, String token, String code) {
    String boundary = "opaa-" + HexFormat.of().formatHex(randomBytes());
    String metadata =
        objectMapper.writeValueAsString(
            Map.of("path", SCRIPT_PATH, "owner", "sandbox", "group", "sandbox", "mode", 644));
    String multipart =
        part(boundary, "metadata", "metadata.json", "application/json", metadata)
            + part(boundary, "file", "main.py", "application/octet-stream", code)
            + "--"
            + boundary
            + "--\r\n";
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(execd + "/files/upload"))
                .header(EXECD_TOKEN_HEADER, token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.UTF_8)),
            HttpResponse.BodyHandlers.ofString());
    requireSuccess(response, "upload script");
  }

  private static String part(
      String boundary, String name, String fileName, String contentType, String content) {
    return "--"
        + boundary
        + "\r\nContent-Disposition: form-data; name=\""
        + name
        + "\"; filename=\""
        + fileName
        + "\"\r\nContent-Type: "
        + contentType
        + "\r\n\r\n"
        + content
        + "\r\n";
  }

  /**
   * Runs the script and reads execd's event stream: bare JSON objects separated by blank lines. The
   * stream ends with {@code execution_complete} (exit code 0) or an {@code error} whose {@code
   * evalue} is the exit code, {@code -1} when the process was killed.
   */
  private ScriptRun execute(String execd, String token, Duration runTimeout) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("argv", List.of("python3", SCRIPT_PATH));
    body.put("cwd", "/workspace");
    body.put("timeout", runTimeout.toMillis());
    body.put("uid", SANDBOX_UID);
    body.put("gid", SANDBOX_UID);
    long started = System.nanoTime();
    HttpResponse<InputStream> response =
        send(
            HttpRequest.newBuilder(URI.create(execd + "/command"))
                .header(EXECD_TOKEN_HEADER, token)
                .header("Content-Type", "application/json")
                .timeout(runTimeout.plus(CALL_TIMEOUT))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))),
            HttpResponse.BodyHandlers.ofInputStream());
    ScriptRun run;
    try (InputStream events = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw new SandboxException("run command answered " + response.statusCode());
      }
      run = readEvents(events, objectMapper);
    } catch (IOException e) {
      throw new SandboxException("reading the run's output failed", e);
    }
    boolean timedOut =
        run.exitCode() == -1
            && Duration.ofNanos(System.nanoTime() - started).compareTo(runTimeout) >= 0;
    return new ScriptRun(run.exitCode(), run.stdout(), run.stderr(), timedOut);
  }

  /**
   * Reads execd's event stream: bare JSON objects separated by blank lines, an optional {@code
   * data:} prefix tolerated. Ends at {@code execution_complete} (exit code 0) or an {@code error}
   * whose {@code evalue} is the exit code; output beyond {@link #MAX_STREAM_CHARS} is dropped, a
   * line beyond {@link #MAX_EVENT_CHARS} fails the run.
   */
  static ScriptRun readEvents(InputStream body, ObjectMapper objectMapper) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    StringBuilder stdout = new StringBuilder();
    StringBuilder stderr = new StringBuilder();
    String line;
    while ((line = readBoundedLine(reader)) != null) {
      String json = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
      if (json.isEmpty()) {
        continue;
      }
      JsonNode event = objectMapper.readTree(json);
      switch (event.path("type").asString()) {
        case "stdout" -> append(stdout, event.path("text").asString());
        case "stderr" -> append(stderr, event.path("text").asString());
        case "execution_complete" -> {
          return new ScriptRun(0, stdout.toString(), stderr.toString(), false);
        }
        case "error" -> {
          int exitCode = parseExitCode(event.path("error").path("evalue").asString());
          return new ScriptRun(exitCode, stdout.toString(), stderr.toString(), false);
        }
        default -> {}
      }
    }
    throw new SandboxException("run ended without a result");
  }

  private static String readBoundedLine(BufferedReader reader) throws IOException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1) {
      if (c == '\n') {
        return line.toString();
      }
      if (line.length() == MAX_EVENT_CHARS) {
        throw new SandboxException("an event line of the run exceeds its limit");
      }
      line.append((char) c);
    }
    return line.isEmpty() ? null : line.toString();
  }

  private JsonNode listOutput(String execd, String token) {
    HttpResponse<InputStream> listing =
        send(
            HttpRequest.newBuilder(
                    URI.create(execd + "/directories/list?path=" + encode(OUTPUT_DIR) + "&depth=1"))
                .header(EXECD_TOKEN_HEADER, token)
                .GET(),
            HttpResponse.BodyHandlers.ofInputStream());
    byte[] listingBytes;
    try (InputStream body = listing.body()) {
      if (listing.statusCode() / 100 != 2) {
        throw new SandboxException("list output directory answered " + listing.statusCode());
      }
      listingBytes = body.readNBytes(MAX_LISTING_BYTES + 1);
    } catch (IOException e) {
      throw new SandboxException("reading the output directory failed", e);
    }
    if (listingBytes.length > MAX_LISTING_BYTES) {
      throw new SandboxException("the output directory listing exceeds its limit");
    }
    return objectMapper.readTree(listingBytes);
  }

  /** The plain files directly in {@link #OUTPUT_DIR}, at most {@link #MAX_LISTED} of them. */
  static List<OutputEntry> entries(JsonNode listing) {
    List<OutputEntry> entries = new ArrayList<>();
    for (JsonNode entry : listing) {
      String name = directFileName(entry);
      if (name != null && entries.size() < MAX_LISTED) {
        entries.add(new OutputEntry(name, Math.max(0, entry.path("size").asLong())));
      }
    }
    return entries;
  }

  /** The bare name of a plain file directly in {@link #OUTPUT_DIR}, otherwise {@code null}. */
  private static String directFileName(JsonNode entry) {
    String path = entry.path("path").asString();
    String name = path.substring(path.lastIndexOf('/') + 1);
    if (!"file".equals(entry.path("type").asString())
        || !path.equals(OUTPUT_DIR + "/" + name)
        || name.isBlank()) {
      return null;
    }
    return name;
  }

  /**
   * The files of {@code entries} that {@link OutputSelection} takes under {@code accepted}, read
   * until {@code expired} says the time is up; every other entry is skipped unread.
   */
  static List<OutputFile> outputFiles(
      List<OutputEntry> entries,
      Predicate<String> accepted,
      BooleanSupplier expired,
      Function<String, byte[]> download) {
    List<OutputSelection.Verdict> verdicts = OutputSelection.of(entries, accepted);
    List<OutputFile> files = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      String name = entries.get(i).name();
      if (verdicts.get(i) != OutputSelection.Verdict.TAKEN) {
        log.info("Sandbox output file skipped: {}", verdicts.get(i));
        continue;
      }
      if (expired.getAsBoolean()) {
        log.info("Sandbox output file skipped: time to collect is up");
        continue;
      }
      byte[] content = download.apply(OUTPUT_DIR + "/" + name);
      if (content == null) {
        log.info("Sandbox output file skipped: larger than announced");
        continue;
      }
      files.add(new OutputFile(name, content));
    }
    return files;
  }

  /** One output file, {@code null} when it is larger than {@link #MAX_FILE_BYTES}. */
  private byte[] download(String execd, String token, String path) {
    HttpResponse<InputStream> download =
        send(
            HttpRequest.newBuilder(URI.create(execd + "/files/download?path=" + encode(path)))
                .header(EXECD_TOKEN_HEADER, token)
                .GET(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = download.body()) {
      if (download.statusCode() != 200) {
        throw new SandboxException("download answered " + download.statusCode());
      }
      byte[] content = body.readNBytes((int) MAX_FILE_BYTES + 1);
      return content.length > MAX_FILE_BYTES ? null : content;
    } catch (IOException e) {
      throw new SandboxException("downloading an output file failed", e);
    }
  }

  private void delete(String sandboxId) {
    try {
      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/v1/sandboxes/" + sandboxId))
                  .header(API_KEY_HEADER, apiKey)
                  .DELETE(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2 && response.statusCode() != 404) {
        log.warn("Deleting sandbox {} answered {}", sandboxId, response.statusCode());
      }
    } catch (SandboxException e) {
      log.warn("Deleting sandbox {} failed; its TTL removes it", sandboxId, e);
    }
  }

  private <T> HttpResponse<T> send(
      HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) {
    HttpRequest built = request.build();
    if (built.timeout().isEmpty()) {
      built = HttpRequest.newBuilder(built, (name, value) -> true).timeout(CALL_TIMEOUT).build();
    }
    try {
      return httpClient.send(built, handler);
    } catch (IOException e) {
      throw new SandboxException("sandbox server unreachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("interrupted while calling the sandbox server", e);
    }
  }

  private static void requireSuccess(HttpResponse<String> response, String step) {
    if (response.statusCode() / 100 != 2) {
      throw new SandboxException(step + " answered " + response.statusCode());
    }
  }

  private static void append(StringBuilder target, String text) {
    int room = MAX_STREAM_CHARS - target.length();
    if (room > 0) {
      target.append(text, 0, Math.min(room, text.length()));
    }
  }

  private static int parseExitCode(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private byte[] randomBytes() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return bytes;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("interrupted while waiting for the sandbox", e);
    }
  }
}
