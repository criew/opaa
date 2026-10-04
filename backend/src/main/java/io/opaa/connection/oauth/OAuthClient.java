package io.opaa.connection.oauth;

import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.SourceFormPost;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one way to an authorization server for OAuth (RFC 6749, 7636, 7009): the authorization
 * request, the code exchange, the renewal and the revocation. Every request goes through {@link
 * SourceFormPost} - target check first, no redirect, bounded answer - and the profile's proxy,
 * always with the certificate check. Client secret, code, verifier and tokens reach no log and no
 * message.
 */
public final class OAuthClient {

  private static final Logger log = LoggerFactory.getLogger(OAuthClient.class);

  /** Assumed where a token answer names no lifetime. */
  static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  static final String TOKEN_ENDPOINT = "Token-Endpunkt";
  private static final String REVOCATION_ENDPOINT = "Widerrufs-Endpunkt";
  private static final long MAX_RESPONSE_BYTES = 64 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TargetAddressValidator targetAddressValidator;

  public OAuthClient(TargetAddressValidator targetAddressValidator) {
    this.targetAddressValidator = targetAddressValidator;
  }

  /**
   * Tokens an authorization server answered with; {@link #toString} shows no value.
   *
   * @param refreshToken {@code null} where the answer carried none
   * @param refreshTokenExpiresAt {@code null} where the answer named no end
   */
  public record Grant(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
      return "Grant[tokens=***, accessTokenExpiresAt=" + accessTokenExpiresAt + "]";
    }
  }

  /**
   * The authorization request the browser is sent to: code flow with {@code state} and the S256
   * {@code challenge}, returning to {@code redirectUri}, with the profile's scopes or the declared
   * ones and the connector's own parameters.
   *
   * @throws SourceCredentialsException when the endpoint cannot be resolved
   */
  public URI authorizationUrl(
      ClientRegistration registration,
      OAuthAuth auth,
      String state,
      String challenge,
      URI redirectUri) {
    URI endpoint = resolve(auth.authorization(), registration, ProfileEndpoints::authorization);
    Map<String, String> query = new LinkedHashMap<>();
    query.put("response_type", "code");
    query.put("client_id", registration.clientId());
    query.put("redirect_uri", redirectUri.toString());
    String scope = scopeOf(registration, auth);
    if (scope != null) {
      query.put("scope", scope);
    }
    query.put("state", state);
    query.put("code_challenge", challenge);
    query.put("code_challenge_method", "S256");
    query.putAll(auth.authorizationParams());
    String base = endpoint.toString();
    return URI.create(base + (endpoint.getRawQuery() == null ? "?" : "&") + encode(query));
  }

  /**
   * Exchanges {@code code} with {@code verifier} for tokens.
   *
   * @throws SourceCredentialsException with a German cause for every failure
   */
  public Grant exchange(
      ClientRegistration registration,
      OAuthAuth auth,
      String code,
      String verifier,
      URI redirectUri,
      Instant now) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri.toString());
    form.put("code_verifier", verifier);
    return tokenRequest(registration, auth, form, now, false);
  }

  /**
   * Renews with {@code refreshToken}; a rotated refresh token comes back in the grant.
   *
   * @throws SignInRejectedException when the server no longer takes the refresh token ({@code
   *     invalid_grant})
   * @throws SourceCredentialsException with a German cause for every other failure, such as an
   *     unreachable endpoint or a refused client registration
   */
  public Grant refresh(
      ClientRegistration registration, OAuthAuth auth, String refreshToken, Instant now) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    return tokenRequest(registration, auth, form, now, true);
  }

  /**
   * Revokes {@code refreshToken} (RFC 7009; the access token where no refresh token is known) or,
   * for a bearer revocation, {@code accessToken}. Returns whether the server took it; a failure is
   * logged without any token and never thrown.
   */
  public boolean revoke(
      ClientRegistration registration, OAuthAuth auth, String refreshToken, String accessToken) {
    Revocation revocation = auth.revocation();
    if (revocation instanceof Revocation.None) {
      return false;
    }
    try {
      URI endpoint = resolve(revocation.endpoint(), registration, ProfileEndpoints::revocation);
      Map<String, String> form = new LinkedHashMap<>();
      Map<String, String> headers = new LinkedHashMap<>();
      switch (revocation) {
        case Revocation.Rfc7009 ignored -> {
          if (refreshToken != null) {
            form.put("token", refreshToken);
            form.put("token_type_hint", "refresh_token");
          } else if (accessToken != null) {
            form.put("token", accessToken);
            form.put("token_type_hint", "access_token");
          } else {
            return false;
          }
          authenticate(registration, auth, form, headers);
        }
        case Revocation.BearerPost ignored -> {
          if (accessToken == null) {
            return false;
          }
          headers.put("Authorization", "Bearer " + accessToken);
        }
        case Revocation.None ignored -> {
          return false;
        }
      }
      SourceFormPost.Response response =
          post(registration, endpoint, form, headers, TIMEOUT, REVOCATION_ENDPOINT);
      if (!response.isSuccess()) {
        log.warn(
            "Revocation endpoint {} refused a token of profile {} (HTTP {})",
            endpoint,
            registration.profileId(),
            response.statusCode());
        return false;
      }
      return true;
    } catch (SourceCredentialsException e) {
      log.warn(
          "A token of profile {} could not be revoked: {}",
          registration.profileId(),
          e.getMessage());
      return false;
    } catch (RuntimeException e) {
      log.warn(
          "A token of profile {} could not be revoked ({})",
          registration.profileId(),
          e.getClass().getSimpleName());
      return false;
    }
  }

  private Grant tokenRequest(
      ClientRegistration registration,
      OAuthAuth auth,
      Map<String, String> form,
      Instant now,
      boolean renewal) {
    URI endpoint = resolve(auth.token(), registration, ProfileEndpoints::token);
    Map<String, String> headers = new LinkedHashMap<>();
    authenticate(registration, auth, form, headers);
    SourceFormPost.Response response =
        post(registration, endpoint, form, headers, TIMEOUT, TOKEN_ENDPOINT);
    JsonNode body = readBody(response);
    if (!response.isSuccess()) {
      String error = field(body, "error");
      log.warn(
          "Token endpoint {} refused a {} of profile {} (HTTP {}, {})",
          endpoint,
          renewal ? "renewal" : "code exchange",
          registration.profileId(),
          response.statusCode(),
          error.isEmpty() ? "no error code" : error);
      if (renewal && "invalid_grant".equals(error)) {
        throw new SignInRejectedException(
            "Der Anbieter nimmt die Zustimmung nicht mehr an; das Konto muss neu verbunden"
                + " werden.");
      }
      if ("invalid_client".equals(error)) {
        // the registration's, never the person's: it ends no connection
        throw new SourceCredentialsException(
            "Der Token-Endpunkt "
                + endpoint
                + " hat die App-Registrierung des Zugangs abgewiesen: Client-ID oder"
                + " Client-Secret werden nicht angenommen. Zuständig ist die Systemverwaltung.");
      }
      if ("invalid_grant".equals(error)) {
        throw new SourceCredentialsException(
            "Der Anbieter hat die Zustimmung nicht bestätigt; sie ist abgelaufen oder schon"
                + " verwendet. Bitte verbinden Sie erneut.");
      }
      throw new SourceCredentialsException(
          "Der Token-Endpunkt "
              + endpoint
              + " hat die Anmeldung abgewiesen (HTTP "
              + response.statusCode()
              + ").");
    }
    String accessToken = field(body, "access_token");
    if (accessToken.isBlank()) {
      throw new SourceCredentialsException("Der Token-Endpunkt hat kein Zugriffstoken geliefert.");
    }
    String refreshToken = field(body, "refresh_token");
    return new Grant(
        accessToken,
        now.plus(lifetime(body, "expires_in").orElse(DEFAULT_LIFETIME)),
        refreshToken.isBlank() ? null : refreshToken,
        refreshToken.isBlank()
            ? null
            : lifetime(body, "refresh_expires_in")
                .or(() -> lifetime(body, "refresh_token_expires_in"))
                .map(now::plus)
                .orElse(null));
  }

  /** Client id and secret as the connector declares; without a secret a public client. */
  private static void authenticate(
      ClientRegistration registration,
      OAuthAuth auth,
      Map<String, String> form,
      Map<String, String> headers) {
    String secret = registration.secret();
    if (secret == null) {
      form.put("client_id", registration.clientId());
      return;
    }
    if (auth.clientAuth() == ClientAuthentication.CLIENT_SECRET_BASIC) {
      headers.put(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (urlEncode(registration.clientId()) + ":" + urlEncode(secret))
                          .getBytes(StandardCharsets.UTF_8)));
    } else {
      form.put("client_id", registration.clientId());
      form.put("client_secret", secret);
    }
  }

  /**
   * The address of {@code endpoint} for {@code registration}: fixed, completed with its tenant, or
   * the one the profile names.
   *
   * @throws SourceCredentialsException when the profile names none or the tenant is not valid
   */
  static URI resolve(
      Endpoint endpoint,
      ClientRegistration registration,
      Function<ProfileEndpoints, String> fromProfile) {
    if (endpoint instanceof Endpoint.FromProfile) {
      String named = fromProfile.apply(registration.endpoints());
      if (named == null) {
        throw new SourceCredentialsException(
            "Der Zugang nennt den Endpunkt des Anbieters nicht. Zuständig ist die"
                + " Systemverwaltung.");
      }
      return URI.create(named);
    }
    try {
      return endpoint.resolve(registration.tenant());
    } catch (IllegalArgumentException e) {
      throw new SourceCredentialsException("Der Mandant des Zugangs fehlt oder ist ungültig.");
    }
  }

  /**
   * Posts {@code form} with {@code headers} to {@code endpoint} - the one call of {@link
   * SourceFormPost} in this package - through the profile's proxy, with the certificate check.
   *
   * @throws SourceCredentialsException with a German cause naming the {@code label}led endpoint
   */
  SourceFormPost.Response post(
      ClientRegistration registration,
      URI endpoint,
      Map<String, String> form,
      Map<String, String> headers,
      Duration timeout,
      String label) {
    try {
      ProxyAndCredentials proxy = ProxyAndCredentials.parse(registration.proxy(), null);
      return SourceFormPost.post(
          SourceHttpClientFactory.buildHttpClient(proxy.proxyHost(), proxy.proxyPort(), false),
          endpoint,
          form,
          headers,
          timeout,
          MAX_RESPONSE_BYTES,
          targetAddressValidator);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (BoundedStreams.LimitExceededException e) {
      throw new SourceCredentialsException(
          "Der " + label + " " + endpoint + " hat eine zu große Antwort geliefert.");
    } catch (IOException e) {
      log.warn("{} {} not reachable: {}", label, endpoint, e.getClass().getSimpleName());
      throw new SourceCredentialsException(
          "Der " + label + " " + endpoint + " ist nicht erreichbar.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceCredentialsException("Der Abruf beim Anbieter wurde unterbrochen.");
    }
  }

  private static String scopeOf(ClientRegistration registration, OAuthAuth auth) {
    return registration.scopes() != null ? registration.scopes() : auth.defaultScopes();
  }

  private static Optional<Duration> lifetime(JsonNode body, String name) {
    JsonNode value = body == null ? null : body.get(name);
    if (value == null || !value.canConvertToLong() || value.asLong() <= 0) {
      return Optional.empty();
    }
    return Optional.of(Duration.ofSeconds(value.asLong()));
  }

  private static String encode(Map<String, String> query) {
    StringBuilder encoded = new StringBuilder();
    for (Map.Entry<String, String> entry : query.entrySet()) {
      if (!encoded.isEmpty()) {
        encoded.append('&');
      }
      encoded.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
    }
    return encoded.toString();
  }

  /** {@code application/x-www-form-urlencoded}, as RFC 6749, 2.3.1 and appendix B ask. */
  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static JsonNode readBody(SourceFormPost.Response response) {
    try {
      return JSON.readTree(response.bodyText());
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String field(JsonNode body, String name) {
    if (body == null || body.get(name) == null || !body.get(name).isString()) {
      return "";
    }
    return body.get(name).asString();
  }
}
