package io.opaa.connection.oauth;

import io.opaa.connection.profile.ClientRegistration;
import io.opaa.indexing.source.ClientCredentialsAuth;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client credentials grant (RFC 6749, 4.4) of a profile: client id and secret, as the connector
 * declares ({@code client_secret_basic} or {@code _post}), go to the declared token endpoint
 * through {@link SourceFormPost} and the profile's proxy, always with the certificate check. Id,
 * secret and token reach no log and no message.
 */
final class ClientCredentialsGrant {

  private static final Logger log = LoggerFactory.getLogger(ClientCredentialsGrant.class);

  static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final long MAX_RESPONSE_BYTES = 64 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TargetAddressValidator targetAddressValidator;

  ClientCredentialsGrant(TargetAddressValidator targetAddressValidator) {
    this.targetAddressValidator = targetAddressValidator;
  }

  /**
   * An access token for {@code registration}, carrying when it expires.
   *
   * @throws SignInRejectedException when the endpoint refuses the client ({@code invalid_client})
   * @throws SourceCredentialsException with a German cause for every other failure
   */
  Secret token(ClientRegistration registration, ClientCredentialsAuth auth, Instant now) {
    URI endpoint;
    try {
      endpoint = auth.token().resolve(registration.tenant());
    } catch (IllegalArgumentException e) {
      throw new SourceCredentialsException("Der Mandant des Zugangs fehlt oder ist ungültig.");
    }
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "client_credentials");
    String scope = registration.scopes() != null ? registration.scopes() : auth.defaultScope();
    if (scope != null) {
      form.put("scope", scope);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    switch (auth.clientAuth()) {
      case CLIENT_SECRET_BASIC ->
          headers.put(
              "Authorization",
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(
                          (encode(registration.clientId()) + ":" + encode(registration.secret()))
                              .getBytes(StandardCharsets.UTF_8)));
      case CLIENT_SECRET_POST -> {
        form.put("client_id", registration.clientId());
        form.put("client_secret", registration.secret());
      }
    }
    SourceFormPost.Response response = post(registration, endpoint, form, headers);
    JsonNode body = readBody(response);
    if (!response.isSuccess()) {
      String error = field(body, "error");
      log.warn(
          "Token endpoint {} refused the client of profile {} (HTTP {}, {})",
          endpoint,
          registration.profileId(),
          response.statusCode(),
          error.isEmpty() ? "no error code" : error);
      if ("invalid_client".equals(error) || error.isEmpty() && response.statusCode() == 401) {
        throw new SignInRejectedException(
            "Der Token-Endpunkt "
                + endpoint
                + " hat die App-Registrierung des Zugangs abgewiesen: Client-ID oder"
                + " Client-Secret werden nicht angenommen.");
      }
      if ("invalid_scope".equals(error)) {
        throw new SourceCredentialsException("Der Scope des Zugangs wird nicht angenommen.");
      }
      throw new SourceCredentialsException(
          "Der Token-Endpunkt "
              + endpoint
              + " hat die Anmeldung abgewiesen (HTTP "
              + response.statusCode()
              + ").");
    }
    JsonNode accessToken = body == null ? null : body.get("access_token");
    if (accessToken == null || !accessToken.isString() || accessToken.asString().isBlank()) {
      throw new SourceCredentialsException("Der Token-Endpunkt hat kein Zugriffstoken geliefert.");
    }
    JsonNode expiresIn = body.get("expires_in");
    long seconds =
        expiresIn != null && expiresIn.canConvertToLong() && expiresIn.asLong() > 0
            ? expiresIn.asLong()
            : DEFAULT_LIFETIME.toSeconds();
    return new Secret(SecretKind.ACCESS_TOKEN, accessToken.asString(), now.plusSeconds(seconds));
  }

  private SourceFormPost.Response post(
      ClientRegistration registration,
      URI endpoint,
      Map<String, String> form,
      Map<String, String> headers) {
    try {
      ProxyAndCredentials proxy = ProxyAndCredentials.parse(registration.proxy(), null);
      return SourceFormPost.post(
          SourceHttpClientFactory.buildHttpClient(proxy.proxyHost(), proxy.proxyPort(), false),
          endpoint,
          form,
          headers,
          TIMEOUT,
          MAX_RESPONSE_BYTES,
          targetAddressValidator);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (BoundedStreams.LimitExceededException e) {
      throw new SourceCredentialsException(
          "Der Token-Endpunkt " + endpoint + " hat eine zu große Antwort geliefert.");
    } catch (IOException e) {
      log.warn("Token endpoint {} not reachable: {}", endpoint, e.getClass().getSimpleName());
      throw new SourceCredentialsException(
          "Der Token-Endpunkt " + endpoint + " ist nicht erreichbar.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceCredentialsException("Der Abruf des Zugriffstokens wurde unterbrochen.");
    }
  }

  /** {@code application/x-www-form-urlencoded} of id and secret, as RFC 6749, 2.3.1 asks. */
  private static String encode(String value) {
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
