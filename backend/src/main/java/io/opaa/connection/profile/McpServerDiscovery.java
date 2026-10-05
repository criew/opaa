package io.opaa.connection.profile;

import io.opaa.common.ValidationException;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.RedirectFollowingFetcher.RedirectPolicy;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the authorization server of an MCP server when its profile is saved, never at run time: the
 * Protected Resource Metadata (RFC 9728) at the server's well-known address, then the authorization
 * server's metadata (RFC 8414, else OpenID discovery). No answer is taken on trust: the resource
 * must name the server, the issuer the address its metadata was read from, PKCE must offer {@code
 * S256}, and every endpoint must be {@code https} - {@code http} only on a loopback host, which the
 * target check refuses in production. Every address passes the target check; a redirect leaves no
 * origin.
 */
@Component
public class McpServerDiscovery {

  private static final Logger log = LoggerFactory.getLogger(McpServerDiscovery.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final long MAX_RESPONSE_BYTES = 64 * 1024;
  private static final String PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource";
  private static final String AUTHORIZATION_SERVER = "/.well-known/oauth-authorization-server";
  private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TargetAddressValidator targetAddressValidator;

  public McpServerDiscovery(TargetAddressValidator targetAddressValidator) {
    this.targetAddressValidator = targetAddressValidator;
  }

  /**
   * What the authorization server of the MCP server {@code resource} names.
   *
   * @param scopes the scopes the server names as supported, {@code null} for none
   */
  public record Metadata(
      String issuer,
      String authorizationEndpoint,
      String tokenEndpoint,
      String revocationEndpoint,
      String scopes) {

    ProfileEndpoints endpoints() {
      return new ProfileEndpoints(authorizationEndpoint, tokenEndpoint, revocationEndpoint);
    }
  }

  /**
   * Discovers the authorization server of the MCP server at {@code resource}, its canonical address
   * ({@link McpServerResource#canonical}).
   *
   * @throws ValidationException (German 400) naming what was not found or not trusted
   */
  public Metadata discover(String resource) {
    URI server = URI.create(resource);
    JsonNode protectedResource =
        firstFound(wellKnown(server, PROTECTED_RESOURCE), "die Metadaten des MCP-Servers");
    String named = text(protectedResource, "resource");
    if (named == null || !resource.equals(canonicalOrNull(named))) {
      throw new ValidationException(
          "Der MCP-Server nennt in seinen Metadaten eine andere Ressource ("
              + shown(named)
              + ") als seine Adresse "
              + resource
              + ".");
    }
    JsonNode servers = protectedResource.get("authorization_servers");
    if (servers == null || !servers.isArray() || servers.isEmpty() || !servers.get(0).isString()) {
      throw new ValidationException(
          "Der MCP-Server nennt keinen Autorisierungsserver (authorization_servers).");
    }
    String issuer = servers.get(0).asString();
    URI issuerUri = secureAddress(issuer, "Der Autorisierungsserver");
    JsonNode metadata =
        firstFound(
            authorizationServerAddresses(issuerUri), "die Metadaten des Autorisierungsservers");
    String announced = text(metadata, "issuer");
    if (!issuer.equals(announced)) {
      throw new ValidationException(
          "Der Autorisierungsserver meldet sich als "
              + shown(announced)
              + ", seine Metadaten wurden aber für "
              + issuer
              + " abgerufen.");
    }
    if (!contains(metadata.get("code_challenge_methods_supported"), "S256")) {
      throw new ValidationException(
          "Der Autorisierungsserver bietet PKCE mit S256 nicht an; ohne PKCE verbindet OPAA"
              + " nicht.");
    }
    String authorization = endpoint(metadata, "authorization_endpoint", true);
    String token = endpoint(metadata, "token_endpoint", true);
    String revocation = endpoint(metadata, "revocation_endpoint", false);
    return new Metadata(issuer, authorization, token, revocation, scopesOf(protectedResource));
  }

  /** RFC 9728, 3.1 and RFC 8414, 3.1: the well-known suffix before the path, then at the root. */
  private static List<URI> wellKnown(URI base, String suffix) {
    String origin = originOf(base);
    String path = base.getRawPath() == null ? "" : base.getRawPath();
    List<URI> addresses = new ArrayList<>();
    if (!path.isEmpty() && !path.equals("/")) {
      addresses.add(URI.create(origin + suffix + path));
    }
    addresses.add(URI.create(origin + suffix));
    return addresses;
  }

  /** RFC 8414, 3.1, then OpenID discovery inserted and appended, as the MCP authorization asks. */
  private static List<URI> authorizationServerAddresses(URI issuer) {
    String path = issuer.getRawPath() == null ? "" : issuer.getRawPath();
    if (path.isEmpty() || path.equals("/")) {
      return List.of(
          URI.create(originOf(issuer) + AUTHORIZATION_SERVER),
          URI.create(originOf(issuer) + OPENID_CONFIGURATION));
    }
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    return List.of(
        URI.create(originOf(issuer) + AUTHORIZATION_SERVER + trimmed),
        URI.create(originOf(issuer) + OPENID_CONFIGURATION + trimmed),
        URI.create(originOf(issuer) + trimmed + OPENID_CONFIGURATION));
  }

  /** The first of {@code addresses} that answers with a JSON object; a 404 tries the next. */
  private JsonNode firstFound(List<URI> addresses, String what) {
    for (URI address : addresses) {
      JsonNode found = fetch(address, what);
      if (found != null) {
        return found;
      }
    }
    throw new ValidationException(
        "OPAA hat " + what + " nicht gefunden (" + addresses.getFirst() + ").");
  }

  /** The JSON object at {@code address}, {@code null} for a 404. */
  private JsonNode fetch(URI address, String what) {
    HttpClient client = SourceHttpClientFactory.buildHttpClient(null, 0, false);
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    try {
      HttpResponse<InputStream> response =
          RedirectFollowingFetcher.sendFollowingRedirects(
              client,
              address.toString(),
              TIMEOUT,
              Map.of("Accept", "application/json"),
              targetAddressValidator,
              RedirectPolicy.REJECT_OFF_ORIGIN);
      try (InputStream body = response.body()) {
        if (response.statusCode() == 404) {
          return null;
        }
        if (response.statusCode() != 200) {
          throw new ValidationException(
              "Der Abruf von "
                  + address
                  + " für "
                  + what
                  + " ist gescheitert (HTTP "
                  + response.statusCode()
                  + ").");
        }
        JsonNode json =
            JSON.readTree(BoundedStreams.readFullyBefore(body, MAX_RESPONSE_BYTES, deadline));
        if (json == null || !json.isObject()) {
          throw new ValidationException(address + " liefert kein JSON-Objekt.");
        }
        return json;
      }
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      throw new ValidationException(e.getMessage());
    } catch (BoundedStreams.LimitExceededException e) {
      throw new ValidationException(address + " hat eine zu große Antwort geliefert.");
    } catch (JacksonException e) {
      throw new ValidationException(address + " liefert kein gültiges JSON.");
    } catch (IOException e) {
      log.info("MCP discovery at {} failed: {}", address, e.getClass().getSimpleName());
      throw new ValidationException(address + " ist nicht erreichbar.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Der Abruf von " + address + " wurde unterbrochen.");
    }
  }

  private static String endpoint(JsonNode metadata, String name, boolean required) {
    String value = text(metadata, name);
    if (value == null) {
      if (required) {
        throw new ValidationException("Der Autorisierungsserver nennt keinen " + name + ".");
      }
      return null;
    }
    secureAddress(value, "Der " + name);
    return value;
  }

  /**
   * {@code address} once it is an absolute {@code https} address without user info or fragment -
   * {@code http} only on a loopback host.
   */
  private static URI secureAddress(String address, String label) {
    URI uri;
    try {
      uri = new URI(address);
    } catch (URISyntaxException e) {
      throw new ValidationException(label + " hat keine gültige Adresse.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || !(scheme.equals("https")
            || scheme.equals("http") && McpServerResource.isLoopback(uri.getHost()))) {
      throw new ValidationException(label + " muss über https erreichbar sein (" + address + ").");
    }
    return uri;
  }

  private static String originOf(URI uri) {
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  private static String canonicalOrNull(String address) {
    try {
      return McpServerResource.canonical(address);
    } catch (ValidationException e) {
      return null;
    }
  }

  private static String scopesOf(JsonNode protectedResource) {
    JsonNode scopes = protectedResource.get("scopes_supported");
    if (scopes == null || !scopes.isArray()) {
      return null;
    }
    List<String> names = new ArrayList<>();
    for (JsonNode scope : scopes) {
      if (scope.isString() && !scope.asString().isBlank()) {
        names.add(scope.asString().strip());
      }
    }
    return names.isEmpty() ? null : String.join(" ", names);
  }

  private static boolean contains(JsonNode array, String value) {
    if (array == null || !array.isArray()) {
      return false;
    }
    for (JsonNode element : array) {
      if (element.isString() && value.equals(element.asString())) {
        return true;
      }
    }
    return false;
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || !value.isString() || value.asString().isBlank()
        ? null
        : value.asString();
  }

  private static String shown(String value) {
    return value == null ? "keine" : value;
  }
}
