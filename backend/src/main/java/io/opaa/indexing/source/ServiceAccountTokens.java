package io.opaa.indexing.source;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.SourceFormPost;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exchanges a service account key for an access token (ADR-0040, Entscheidung 2): a JWT assertion
 * after RFC 7523, signed RS256 with the key, posted to the token endpoint of the connector's
 * description through {@link SourceFormPost}. A token is reused until shortly before it expires, so
 * a run asking per request costs one exchange an hour. Key, assertion and token reach no log and no
 * message.
 */
public class ServiceAccountTokens {

  private static final Logger log = LoggerFactory.getLogger(ServiceAccountTokens.class);

  static final Duration ASSERTION_LIFETIME = Duration.ofHours(1);
  static final Duration RENEWAL_MARGIN = Duration.ofMinutes(5);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final long MAX_RESPONSE_BYTES = 64 * 1024;
  private static final int MAX_CACHED = 1000;
  private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TargetAddressValidator targetAddressValidator;
  private final Clock clock;
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();

  private record Token(String value, Instant renewAt) {}

  public ServiceAccountTokens(TargetAddressValidator targetAddressValidator, Clock clock) {
    this.targetAddressValidator = targetAddressValidator;
    this.clock = clock;
  }

  /**
   * The secret {@code connector} may see for {@code settings}: as stored for a connector without
   * service account sign-in, else the access token for the key in {@code settings} under the
   * subject {@code effective} names; {@code null} without a secret.
   */
  public String forConnector(
      SourceConnector connector, SourceSettings settings, ConnectorData effective) {
    ServiceAccountKeyAuth auth = connector.descriptor().serviceAccountKey();
    if (auth == null || settings.sourceCredentials() == null) {
      return settings.sourceCredentials();
    }
    return accessToken(
        settings.sourceCredentials(),
        subjectOf(connector, effective),
        auth,
        settings.sourceProxy());
  }

  /** The subject {@code connector} imitates under {@code settings}, blank read as none. */
  public static String subjectOf(SourceConnector connector, ConnectorData settings) {
    String subject = connector.assertionSubject(settings);
    return subject == null || subject.isBlank() ? null : subject.trim();
  }

  /**
   * An access token valid now for {@code storedKey}, imitating {@code subject} when given.
   *
   * @param sourceProxy the library's proxy, {@code host:port} or {@code null}
   * @throws SourceCredentialsException with a German cause when the key cannot be used
   */
  public String accessToken(
      String storedKey, String subject, ServiceAccountKeyAuth auth, String sourceProxy) {
    Objects.requireNonNull(storedKey, "storedKey");
    String cacheKey = cacheKey(storedKey, subject, auth, sourceProxy);
    Instant now = clock.instant();
    Token cached = tokens.get(cacheKey);
    if (cached != null && now.isBefore(cached.renewAt())) {
      return cached.value();
    }
    ServiceAccountKey key;
    try {
      key = ServiceAccountKey.parse(storedKey);
    } catch (io.opaa.common.ValidationException e) {
      throw new SourceCredentialsException(
          "Der gespeicherte Dienstkonto-Schlüssel ist nicht lesbar. Bitte den Schlüssel erneut"
              + " hochladen.");
    }
    Token token = exchange(key, subject, auth, sourceProxy, now);
    if (tokens.size() >= MAX_CACHED) {
      tokens.clear();
    }
    tokens.put(cacheKey, token);
    return token.value();
  }

  private Token exchange(
      ServiceAccountKey key,
      String subject,
      ServiceAccountKeyAuth auth,
      String sourceProxy,
      Instant now) {
    String assertion = sign(key, subject, auth, now);
    SourceFormPost.Response response;
    try {
      ProxyAndCredentials proxy = ProxyAndCredentials.parse(sourceProxy, null);
      response =
          SourceFormPost.post(
              SourceHttpClientFactory.buildHttpClient(proxy.proxyHost(), proxy.proxyPort(), false),
              auth.tokenEndpoint(),
              Map.of("grant_type", GRANT_TYPE, "assertion", assertion),
              TIMEOUT,
              MAX_RESPONSE_BYTES,
              targetAddressValidator);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (IOException e) {
      log.warn(
          "Token endpoint {} not reachable: {}",
          auth.tokenEndpoint(),
          e.getClass().getSimpleName());
      throw new SourceCredentialsException(
          "Der Token-Endpunkt " + auth.tokenEndpoint() + " ist nicht erreichbar.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceCredentialsException("Der Abruf des Zugriffstokens wurde unterbrochen.");
    }
    JsonNode body = readBody(response);
    if (!response.isSuccess()) {
      throw new SourceCredentialsException(refusal(response.statusCode(), body, key, subject));
    }
    JsonNode accessToken = body == null ? null : body.get("access_token");
    if (accessToken == null || !accessToken.isString() || accessToken.asString().isBlank()) {
      throw new SourceCredentialsException("Der Token-Endpunkt hat kein Zugriffstoken geliefert.");
    }
    JsonNode expiresIn = body.get("expires_in");
    long seconds =
        expiresIn != null && expiresIn.canConvertToLong()
            ? expiresIn.asLong()
            : ASSERTION_LIFETIME.toSeconds();
    Instant renewAt = now.plusSeconds(seconds).minus(RENEWAL_MARGIN);
    return new Token(accessToken.asString(), renewAt);
  }

  private static String sign(
      ServiceAccountKey key, String subject, ServiceAccountKeyAuth auth, Instant now) {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(key.clientEmail())
            .audience(auth.tokenEndpoint().toString())
            .claim("scope", auth.scope())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ASSERTION_LIFETIME)));
    if (subject != null) {
      claims.subject(subject);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.privateKeyId())
                .build(),
            claims.build());
    try {
      jwt.sign(new RSASSASigner(key.privateKey()));
    } catch (JOSEException e) {
      throw new SourceCredentialsException(
          "Der Dienstkonto-Schlüssel kann nicht signieren. Bitte den Schlüssel erneut hochladen.");
    }
    return jwt.serialize();
  }

  private static JsonNode readBody(SourceFormPost.Response response) {
    try {
      return JSON.readTree(response.bodyText());
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * The German finding for a refused exchange, from the OAuth error of the answer: an unknown or
   * revoked key, a missing domain-wide delegation, an unknown imitated account.
   */
  static String refusal(int status, JsonNode body, ServiceAccountKey key, String subject) {
    String error = field(body, "error");
    String description = field(body, "error_description").toLowerCase(java.util.Locale.ROOT);
    log.warn(
        "Token endpoint refused the assertion of {} (HTTP {}, {})",
        key.clientEmail(),
        status,
        error.isEmpty() ? "no error code" : error);
    if ("unauthorized_client".equals(error)) {
      return subject == null
          ? "Das Dienstkonto " + key.clientEmail() + " darf diesen Zugriff nicht anfordern."
          : "Die domänenweite Delegation fehlt: Das Dienstkonto "
              + key.clientEmail()
              + " darf das Konto "
              + subject
              + " nicht imitieren, oder der Scope ist in der Admin-Konsole nicht freigegeben.";
    }
    if ("invalid_scope".equals(error)) {
      return "Der Scope des Dienstkontos wird nicht angenommen.";
    }
    if ("invalid_grant".equals(error) && subject != null && description.contains("email")) {
      return "Das imitierte Konto " + subject + " ist in der Domäne nicht bekannt.";
    }
    if ("invalid_grant".equals(error) || "invalid_client".equals(error)) {
      return "Der Dienstkonto-Schlüssel von "
          + key.clientEmail()
          + " wird nicht angenommen: Er ist ungültig, widerrufen, oder das Dienstkonto existiert"
          + " nicht mehr.";
    }
    return "Der Token-Endpunkt hat den Dienstkonto-Schlüssel abgewiesen (HTTP " + status + ").";
  }

  private static String field(JsonNode body, String name) {
    if (body == null || body.get(name) == null || !body.get(name).isString()) {
      return "";
    }
    return body.get(name).asString();
  }

  private static String cacheKey(
      String storedKey, String subject, ServiceAccountKeyAuth auth, String sourceProxy) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(storedKey.getBytes(StandardCharsets.UTF_8));
      for (String part :
          new String[] {subject, auth.tokenEndpoint().toString(), auth.scope(), sourceProxy}) {
        digest.update((byte) 0);
        if (part != null) {
          digest.update(part.getBytes(StandardCharsets.UTF_8));
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
