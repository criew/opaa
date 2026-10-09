package io.opaa.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An authorization server on {@code 127.0.0.1} for the client credentials, the JWT bearer, the
 * authorization code (with PKCE, S256 only) and the refresh token grant, with token revocation by
 * RFC 7009 ({@code /revoke}) and by bearer ({@code /revoke-bearer}). It records every request
 * (target, {@code Authorization}, form) and answers with new tokens, or with the OAuth error set by
 * {@link #rejectWith}. {@link #approve} stands for the person consenting in the browser, as the
 * account {@link #account} names; {@link #accountOf} tells which account an access token was issued
 * to. {@link #holdRevocations} stands for a provider that takes a revocation but does not answer.
 * It also answers a request sent to it as a proxy. {@link #shared()} serves the Spring contexts;
 * {@link #overTls()} serves {@code https://} for an endpoint a profile names, which must be one.
 */
public final class FakeAuthorizationServer implements AutoCloseable {

  private static FakeAuthorizationServer shared;

  /** The account every consent is given as until {@link #account} names another. */
  public static final String DEFAULT_ACCOUNT = "dienstkonto@example.org";

  /** One request as it arrived. */
  public record Request(URI target, String authorization, Map<String, String> form) {

    /** The {@code grant_type} of a token request, {@code null} for any other. */
    public String grantType() {
      return form.get("grant_type");
    }
  }

  /** What a consent bound its code to. */
  private record Consent(String clientId, String redirectUri, String challenge) {}

  private final HttpServer server;
  private final String scheme;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger issued = new AtomicInteger();
  private final AtomicInteger refreshIssued = new AtomicInteger();
  private final Map<String, Consent> codes = new ConcurrentHashMap<>();
  private final Set<String> liveRefreshTokens = ConcurrentHashMap.newKeySet();
  private final Map<String, String> accountOfToken = new ConcurrentHashMap<>();
  private volatile String account = DEFAULT_ACCOUNT;
  private volatile String error;
  private volatile int errorStatus;
  private volatile boolean rotateRefreshTokens = true;
  private volatile long accessLifetimeSeconds = 3600;
  private volatile Long refreshLifetimeSeconds;
  private volatile Duration delay = Duration.ZERO;
  private volatile boolean unreachable;
  private volatile Runnable beforeCodeAnswer;
  private volatile CountDownLatch revocationGate;

  public FakeAuthorizationServer() {
    this(false);
  }

  private FakeAuthorizationServer(boolean tls) {
    try {
      if (tls) {
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(LoopbackTls.serverContext()));
        server = https;
      } else {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    scheme = tls ? "https" : "http";
    server.createContext("/token", this::handleToken);
    server.createContext("/revoke", this::handleRevocation);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
  }

  /** The server the test connectors of the Spring contexts are declared with; never stopped. */
  public static synchronized FakeAuthorizationServer shared() {
    if (shared == null) {
      shared = new FakeAuthorizationServer();
    }
    return shared;
  }

  /**
   * A server on {@code https://127.0.0.1} with the certificate of {@link LoopbackTls}, which the
   * default TLS context of this JVM trusts until {@link LoopbackTls#restore}, which the calling
   * test class runs in {@code @AfterAll}; it answers no request as a proxy.
   */
  public static FakeAuthorizationServer overTls() {
    LoopbackTls.trustInThisJvm();
    return new FakeAuthorizationServer(true);
  }

  /** The issuer this server names itself as (RFC 8414), its origin without a path. */
  public String issuer() {
    return scheme + "://127.0.0.1:" + server.getAddress().getPort();
  }

  public URI tokenEndpoint() {
    return endpoint("/token");
  }

  /** Where a person is sent to consent; nothing answers there, {@link #approve} stands for it. */
  public URI authorizationEndpoint() {
    return endpoint("/authorize");
  }

  public URI revocationEndpoint() {
    return endpoint("/revoke");
  }

  public URI bearerRevocationEndpoint() {
    return endpoint("/revoke-bearer");
  }

  private URI endpoint(String path) {
    return URI.create(issuer() + path);
  }

  /** {@code host:port} of this server, to be named as a proxy. */
  public String address() {
    return "127.0.0.1:" + server.getAddress().getPort();
  }

  /** From now on every token request is refused with the OAuth {@code error} and {@code status}. */
  public void rejectWith(int status, String error) {
    this.errorStatus = status;
    this.error = error;
  }

  /** From now on every consent and renewal is given as the account {@code name}. */
  public void account(String name) {
    this.account = name;
  }

  /** The account the access token {@code accessToken} was issued to, {@code null} for unknown. */
  public String accountOf(String accessToken) {
    return accessToken == null ? null : accountOfToken.get(accessToken);
  }

  /** From now on every request gets a token again. */
  public void accept() {
    this.error = null;
  }

  /** Whether a refresh hands out a new refresh token and invalidates the used one (default). */
  public void rotateRefreshTokens(boolean rotate) {
    this.rotateRefreshTokens = rotate;
  }

  /** The {@code expires_in} of every access token from now on. */
  public void accessLifetime(Duration lifetime) {
    this.accessLifetimeSeconds = lifetime.toSeconds();
  }

  /** The {@code refresh_expires_in} of every refresh token from now on, {@code null} for none. */
  public void refreshLifetime(Duration lifetime) {
    this.refreshLifetimeSeconds = lifetime == null ? null : lifetime.toSeconds();
  }

  /** How long every token answer waits before it is sent. */
  public void delay(Duration delay) {
    this.delay = delay;
  }

  /** From now on the token endpoint drops every connection without an answer. */
  public void unreachable(boolean unreachable) {
    this.unreachable = unreachable;
  }

  /**
   * From now on every revocation is recorded on arrival and then left without an answer until
   * {@link #releaseRevocations} or {@link #reset}; a client gives up after its own time limit.
   */
  public void holdRevocations() {
    revocationGate = new CountDownLatch(1);
  }

  /** Answers every held revocation and those that follow. */
  public void releaseRevocations() {
    CountDownLatch gate = revocationGate;
    revocationGate = null;
    if (gate != null) {
      gate.countDown();
    }
  }

  /** Runs {@code hook} once the next code exchange arrived, before it is answered. */
  public void beforeCodeAnswer(Runnable hook) {
    this.beforeCodeAnswer = hook;
  }

  /**
   * The person consents to the authorization request {@code authorizationUrl}: returns the code the
   * provider would send back to its {@code redirect_uri}.
   *
   * @throws IllegalArgumentException for a request without PKCE (S256), client id or redirect
   */
  public String approve(URI authorizationUrl) {
    Map<String, String> query = form(authorizationUrl.getRawQuery());
    if (!"code".equals(query.get("response_type"))
        || !"S256".equals(query.get("code_challenge_method"))
        || query.get("code_challenge") == null
        || query.get("client_id") == null
        || query.get("redirect_uri") == null
        || query.get("state") == null) {
      throw new IllegalArgumentException("no authorization code request with PKCE: " + query);
    }
    String code = "fake-code-" + UUID.randomUUID();
    codes.put(
        code,
        new Consent(
            query.get("client_id"), query.get("redirect_uri"), query.get("code_challenge")));
    return code;
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  /** The requests that asked for {@code grantType}. */
  public List<Request> requests(String grantType) {
    return requests.stream().filter(request -> grantType.equals(request.grantType())).toList();
  }

  /** The requests that arrived at a revocation endpoint. */
  public List<Request> revocations() {
    return requests.stream()
        .filter(request -> request.target().getPath().startsWith("/revoke"))
        .toList();
  }

  /** The access token the last successful answer carried, {@code null} before any. */
  public String lastToken() {
    int n = issued.get();
    return n == 0 ? null : issuedToken(n);
  }

  /** The refresh token the last successful answer carried, {@code null} before any. */
  public String lastRefreshToken() {
    int n = refreshIssued.get();
    return n == 0 ? null : refreshToken(n);
  }

  /** Whether {@code refreshToken} would still be taken. */
  public boolean isLive(String refreshToken) {
    return liveRefreshTokens.contains(refreshToken);
  }

  private static String issuedToken(int n) {
    return "fake-access-token-" + n;
  }

  private static String refreshToken(int n) {
    return "fake-refresh-token-" + n;
  }

  /** Forgets the requests and every setting; tokens keep counting. */
  public void reset() {
    releaseRevocations();
    requests.clear();
    error = null;
    rotateRefreshTokens = true;
    accessLifetimeSeconds = 3600;
    refreshLifetimeSeconds = null;
    delay = Duration.ZERO;
    unreachable = false;
    beforeCodeAnswer = null;
    account = DEFAULT_ACCOUNT;
  }

  private void handleToken(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> form = form(body);
    requests.add(
        new Request(
            exchange.getRequestURI(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            form));
    if (unreachable) {
      exchange.close();
      return;
    }
    pause();
    String rejection = error;
    if (rejection != null) {
      answer(exchange, errorStatus, "{\"error\": \"" + rejection + "\"}");
      return;
    }
    String grantType = form.get("grant_type");
    if ("authorization_code".equals(grantType)) {
      Runnable hook = beforeCodeAnswer;
      beforeCodeAnswer = null;
      if (hook != null) {
        hook.run();
      }
      Consent consent = codes.remove(form.getOrDefault("code", ""));
      if (consent == null
          || !consent.redirectUri().equals(form.get("redirect_uri"))
          || !consent.challenge().equals(challengeOf(form.get("code_verifier")))) {
        answer(exchange, 400, "{\"error\": \"invalid_grant\"}");
        return;
      }
      answer(exchange, 200, tokens(true));
      return;
    }
    if ("refresh_token".equals(grantType)) {
      String used = form.getOrDefault("refresh_token", "");
      if (!liveRefreshTokens.contains(used)) {
        answer(exchange, 400, "{\"error\": \"invalid_grant\"}");
        return;
      }
      if (rotateRefreshTokens) {
        liveRefreshTokens.remove(used);
      }
      answer(exchange, 200, tokens(rotateRefreshTokens));
      return;
    }
    answer(
        exchange,
        200,
        "{\"access_token\": \""
            + issuedToken(issued.incrementAndGet())
            + "\", \"token_type\": \"Bearer\", \"expires_in\": "
            + accessLifetimeSeconds
            + "}");
  }

  private String tokens(boolean withRefresh) {
    String access = issuedToken(issued.incrementAndGet());
    accountOfToken.put(access, account);
    StringBuilder json =
        new StringBuilder("{\"access_token\": \"")
            .append(access)
            .append("\", \"token_type\": \"Bearer\", \"expires_in\": ")
            .append(accessLifetimeSeconds);
    if (withRefresh) {
      String refresh = refreshToken(refreshIssued.incrementAndGet());
      liveRefreshTokens.add(refresh);
      json.append(", \"refresh_token\": \"").append(refresh).append('"');
      if (refreshLifetimeSeconds != null) {
        json.append(", \"refresh_expires_in\": ").append(refreshLifetimeSeconds);
      }
    }
    return json.append('}').toString();
  }

  private void handleRevocation(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> form = form(body);
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    requests.add(new Request(exchange.getRequestURI(), authorization, form));
    CountDownLatch gate = revocationGate;
    if (gate != null) {
      try {
        if (!gate.await(5, TimeUnit.MINUTES)) {
          exchange.close();
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exchange.close();
        return;
      }
    }
    String token = form.get("token");
    if (token != null) {
      liveRefreshTokens.remove(token);
    }
    answer(exchange, 200, "{}");
  }

  private void pause() {
    Duration wait = delay;
    if (wait.isZero()) {
      return;
    }
    try {
      Thread.sleep(wait.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String challengeOf(String verifier) {
    if (verifier == null) {
      return "";
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, String> form(String body) {
    Map<String, String> form = new LinkedHashMap<>();
    if (body == null || body.isEmpty()) {
      return form;
    }
    for (String pair : body.split("&")) {
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      form.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return form;
  }

  private static void answer(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    if (this != shared) {
      server.stop(0);
    }
  }
}
