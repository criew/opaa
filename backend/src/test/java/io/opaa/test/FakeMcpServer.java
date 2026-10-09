package io.opaa.test;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An MCP server on {@code 127.0.0.1} with its own authorization server, for the MCP authorization:
 * Protected Resource Metadata (RFC 9728) for the endpoint {@code /mcp}, the authorization server
 * {@code /auth} with RFC 8414 metadata, the code grant with PKCE (S256) and the refresh grant, both
 * requiring the resource indicator (RFC 8707) to name this server - else {@code invalid_target} -,
 * and RFC 7009 revocation. Every token it issues is bound to this server ({@link #audienceOf}).
 * {@link #approve} stands for the person consenting in the browser; the metadata can be falsified
 * to test what OPAA refuses.
 */
public final class FakeMcpServer implements AutoCloseable {

  /** One request to the authorization server as it arrived. */
  public record Request(String path, String authorization, Map<String, String> form) {

    public String grantType() {
      return form.get("grant_type");
    }
  }

  private record Consent(String clientId, String redirectUri, String challenge, String resource) {}

  private final HttpServer server;
  private final String prefix;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final List<String> metadataRequests = new CopyOnWriteArrayList<>();
  private final Map<String, Consent> codes = new ConcurrentHashMap<>();
  private final Set<String> liveRefreshTokens = ConcurrentHashMap.newKeySet();
  private final Map<String, String> audienceOfToken = new ConcurrentHashMap<>();
  private final AtomicInteger issued = new AtomicInteger();
  private volatile String announcedResource;
  private volatile String announcedIssuer;
  private volatile FakeMcpServer authorizationServer;
  private volatile boolean offersS256 = true;
  private volatile Boolean announcesIssuerParameter;

  public FakeMcpServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    prefix = "mcp" + server.getAddress().getPort() + "-";
    server.createContext("/.well-known/oauth-protected-resource", this::handleResourceMetadata);
    server.createContext("/.well-known/oauth-authorization-server", this::handleServerMetadata);
    server.createContext("/auth/token", this::handleToken);
    server.createContext("/auth/revoke", this::handleRevocation);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
  }

  private String origin() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** The Streamable HTTP endpoint, the resource indicator of this server. */
  public String resource() {
    return origin() + "/mcp";
  }

  /** The issuer of the authorization server, with a path as RFC 8414 allows. */
  public String issuer() {
    return origin() + "/auth";
  }

  public String authorizationEndpoint() {
    return issuer() + "/authorize";
  }

  public String tokenEndpoint() {
    return issuer() + "/token";
  }

  public String revocationEndpoint() {
    return issuer() + "/revoke";
  }

  /** From now on the resource metadata names {@code resource}; {@code null} for the true one. */
  public void announceResource(String resource) {
    this.announcedResource = resource;
  }

  /** From now on the server metadata names {@code issuer}; {@code null} for the true one. */
  public void announceIssuer(String issuer) {
    this.announcedIssuer = issuer;
  }

  /**
   * From now on the resource metadata names the authorization server of {@code other}, as a server
   * that moved to another one; {@code null} for its own.
   */
  public void useAuthorizationServerOf(FakeMcpServer other) {
    this.authorizationServer = other;
  }

  /** Whether the server metadata offers PKCE with S256 (default). */
  public void offerS256(boolean offers) {
    this.offersS256 = offers;
  }

  /**
   * From now on the server metadata names {@code authorization_response_iss_parameter_supported}
   * (RFC 9207) as {@code announces}; {@code null} leaves it out (default).
   */
  public void announceIssuerParameter(Boolean announces) {
    this.announcesIssuerParameter = announces;
  }

  /**
   * The person consents to {@code authorizationUrl}: the code the server would send back.
   *
   * @throws IllegalArgumentException for a request without PKCE or for another resource
   */
  public String approve(URI authorizationUrl) {
    Map<String, String> query = form(authorizationUrl.getRawQuery());
    if (!"code".equals(query.get("response_type"))
        || !"S256".equals(query.get("code_challenge_method"))
        || query.get("code_challenge") == null
        || query.get("state") == null) {
      throw new IllegalArgumentException("no authorization code request with PKCE: " + query);
    }
    if (!resource().equals(query.get("resource"))) {
      throw new IllegalArgumentException("invalid_target: " + query.get("resource"));
    }
    String code = prefix + "code-" + UUID.randomUUID();
    codes.put(
        code,
        new Consent(
            query.get("client_id"),
            query.get("redirect_uri"),
            query.get("code_challenge"),
            query.get("resource")));
    return code;
  }

  /** The requests to the token and revocation endpoints. */
  public List<Request> requests() {
    return List.copyOf(requests);
  }

  public List<Request> requests(String grantType) {
    return requests.stream().filter(request -> grantType.equals(request.grantType())).toList();
  }

  public List<Request> revocations() {
    return requests.stream().filter(request -> request.path().endsWith("/revoke")).toList();
  }

  /** The paths of every metadata document asked for. */
  public List<String> metadataRequests() {
    return List.copyOf(metadataRequests);
  }

  /** The resource the access token {@code token} was issued for, {@code null} for unknown. */
  public String audienceOf(String token) {
    return token == null ? null : audienceOfToken.get(token);
  }

  public boolean isLive(String refreshToken) {
    return liveRefreshTokens.contains(refreshToken);
  }

  private void handleResourceMetadata(HttpExchange exchange) throws IOException {
    metadataRequests.add(exchange.getRequestURI().getPath());
    if (!exchange.getRequestURI().getPath().equals("/.well-known/oauth-protected-resource/mcp")) {
      answer(exchange, 404, "{}");
      return;
    }
    String resource = announcedResource == null ? resource() : announcedResource;
    answer(
        exchange,
        200,
        "{\"resource\": \""
            + resource
            + "\", \"authorization_servers\": [\""
            + (authorizationServer == null ? issuer() : authorizationServer.issuer())
            + "\"], \"scopes_supported\": [\"tools.read\", \"offline_access\"],"
            + " \"bearer_methods_supported\": [\"header\"]}");
  }

  private void handleServerMetadata(HttpExchange exchange) throws IOException {
    metadataRequests.add(exchange.getRequestURI().getPath());
    if (!exchange
        .getRequestURI()
        .getPath()
        .equals("/.well-known/oauth-authorization-server/auth")) {
      answer(exchange, 404, "{}");
      return;
    }
    String issuer = announcedIssuer == null ? issuer() : announcedIssuer;
    answer(
        exchange,
        200,
        "{\"issuer\": \""
            + issuer
            + "\", \"authorization_endpoint\": \""
            + authorizationEndpoint()
            + "\", \"token_endpoint\": \""
            + tokenEndpoint()
            + "\", \"revocation_endpoint\": \""
            + revocationEndpoint()
            + "\", \"response_types_supported\": [\"code\"],"
            + " \"code_challenge_methods_supported\": "
            + (offersS256 ? "[\"S256\"]" : "[\"plain\"]")
            + (announcesIssuerParameter == null
                ? ""
                : ", \"authorization_response_iss_parameter_supported\": "
                    + announcesIssuerParameter)
            + "}");
  }

  private void handleToken(HttpExchange exchange) throws IOException {
    Map<String, String> form = record(exchange);
    if (!resource().equals(form.get("resource"))) {
      answer(exchange, 400, "{\"error\": \"invalid_target\"}");
      return;
    }
    String grantType = form.get("grant_type");
    if ("authorization_code".equals(grantType)) {
      Consent consent = codes.remove(form.getOrDefault("code", ""));
      if (consent == null
          || !consent.redirectUri().equals(form.get("redirect_uri"))
          || !consent.resource().equals(form.get("resource"))
          || !consent.challenge().equals(challengeOf(form.get("code_verifier")))) {
        answer(exchange, 400, "{\"error\": \"invalid_grant\"}");
        return;
      }
      answer(exchange, 200, tokens());
      return;
    }
    if ("refresh_token".equals(grantType)) {
      String used = form.getOrDefault("refresh_token", "");
      if (!liveRefreshTokens.remove(used)) {
        answer(exchange, 400, "{\"error\": \"invalid_grant\"}");
        return;
      }
      answer(exchange, 200, tokens());
      return;
    }
    answer(exchange, 400, "{\"error\": \"unsupported_grant_type\"}");
  }

  private String tokens() {
    int n = issued.incrementAndGet();
    String access = prefix + "access-" + n;
    String refresh = prefix + "refresh-" + n;
    audienceOfToken.put(access, resource());
    liveRefreshTokens.add(refresh);
    return "{\"access_token\": \""
        + access
        + "\", \"token_type\": \"Bearer\", \"expires_in\": 3600, \"refresh_token\": \""
        + refresh
        + "\"}";
  }

  private void handleRevocation(HttpExchange exchange) throws IOException {
    Map<String, String> form = record(exchange);
    String token = form.get("token");
    if (token != null) {
      liveRefreshTokens.remove(token);
    }
    answer(exchange, 200, "{}");
  }

  private Map<String, String> record(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> form = form(body);
    requests.add(
        new Request(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            form));
    return form;
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
    server.stop(0);
  }
}
