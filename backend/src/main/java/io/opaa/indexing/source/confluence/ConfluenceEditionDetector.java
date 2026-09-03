package io.opaa.indexing.source.confluence;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.net.http.HttpClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Detects the edition behind an address without credentials (ADR-0023, Entscheidung 2):
 *
 * <ol>
 *   <li>{@code GET <host root>/_edge/tenant_info} - every Cloud site answers {@code 200} with a
 *       JSON object carrying {@code cloudId}; Data Center has no such path. The Cloud content
 *       endpoints are unusable as a signature: unauthenticated they answer {@code 404},
 *       indistinguishable from a path that does not exist.
 *   <li>Otherwise {@code GET <base>/status} ({@code {"state":"RUNNING"}}) or {@code GET
 *       <base>/rest/api/space?limit=1} ({@code 200}/{@code 401} with a JSON body) identifies Data
 *       Center.
 * </ol>
 *
 * The host name is never consulted: Cloud can sit behind a custom domain, Data Center behind any. A
 * probe that fails at the connection level (a redirect off to an SSO login on another host, a
 * refused connection) counts as "signature not met" and is named in the final message - except a
 * target-validation rejection, which is a configuration problem of its own and surfaces as such.
 */
public final class ConfluenceEditionDetector {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ConfluenceProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final Sleeper sleeper;

  ConfluenceEditionDetector(
      ConfluenceProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.sleeper = sleeper;
  }

  /** The detected edition and the base URL normalised for it. */
  public record Detected(ConfluenceEdition edition, URI baseUrl) {}

  /**
   * @param rawBaseUrl the address as entered; for Cloud with or without {@code /wiki}, for Data
   *     Center including any context path
   * @throws ConfluenceAccessException.NoConfluence when neither signature matches
   * @throws ConfluenceAccessException when the target validation rejects the address
   * @throws ConfluenceConnection.InvalidBaseUrlException when the address is not a usable URL
   */
  public Detected detect(String rawBaseUrl, String proxyHost, int proxyPort, boolean insecureSsl)
      throws ConfluenceAccessException, InterruptedException {
    URI dataCenterBase =
        ConfluenceConnection.normalizeBaseUrl(rawBaseUrl, ConfluenceEdition.DATA_CENTER);
    URI cloudBase = ConfluenceConnection.normalizeBaseUrl(rawBaseUrl, ConfluenceEdition.CLOUD);
    HttpClient client = SourceHttpClientFactory.buildHttpClient(proxyHost, proxyPort, insecureSsl);
    ConfluenceRequestMeter meter = new ConfluenceRequestMeter();
    String resource = "die Editionserkennung";

    ConfluenceConnection cloud =
        new ConfluenceConnection(
            cloudBase, ConfluenceEdition.CLOUD, null, proxyHost, proxyPort, insecureSsl);
    ConfluenceHttp cloudHttp =
        new ConfluenceHttp(client, cloud, properties, targetAddressValidator, sleeper, meter);
    ProbeOutcome tenant = probe(cloudHttp, hostRoot(cloudBase) + "/_edge/tenant_info", resource);
    if (tenant.response != null
        && tenant.response.status() == 200
        && jsonHas(tenant.response, "cloudId")) {
      return new Detected(ConfluenceEdition.CLOUD, cloudBase);
    }

    ConfluenceConnection dataCenter =
        new ConfluenceConnection(
            dataCenterBase, ConfluenceEdition.DATA_CENTER, null, proxyHost, proxyPort, insecureSsl);
    ConfluenceHttp dcHttp =
        new ConfluenceHttp(client, dataCenter, properties, targetAddressValidator, sleeper, meter);
    ProbeOutcome status = probe(dcHttp, dataCenter.url("/status"), resource);
    if (status.response != null
        && status.response.status() == 200
        && jsonHas(status.response, "state")) {
      return new Detected(ConfluenceEdition.DATA_CENTER, dataCenterBase);
    }
    ProbeOutcome spaces =
        probe(dcHttp, dataCenter.url(DataCenterConfluenceClient.REST + "/space?limit=1"), resource);
    if (spaces.response != null
        && (spaces.response.status() == 200 || spaces.response.status() == 401)
        && isJsonObject(spaces.response)) {
      return new Detected(ConfluenceEdition.DATA_CENTER, dataCenterBase);
    }

    StringBuilder message =
        new StringBuilder("Unter ")
            .append(dataCenterBase.getHost())
            .append(
                " wurde kein Confluence erkannt - weder die Cloud-Signatur (/_edge/tenant_info) noch"
                    + " die Data-Center-API (/status, /rest/api) antworten wie erwartet.");
    ProbeOutcome failed =
        tenant.failure != null ? tenant : status.failure != null ? status : spaces;
    if (failed.failure != null) {
      message
          .append(" Letzter Fehler: ")
          .append(failed.failure.getMessage())
          .append(
              " Leitet ein vorgeschalteter Anmeldedienst (SSO) jede Anfrage auf eine Anmeldeseite"
                  + " um, kann OPAA die REST-API nicht erreichen; die Instanz muss API-Zugriffe mit"
                  + " Token ohne Browser-Anmeldung zulassen.");
    }
    throw new ConfluenceAccessException.NoConfluence(message.toString());
  }

  private record ProbeOutcome(
      ConfluenceHttp.Response response, ConfluenceAccessException failure) {}

  /**
   * Runs one probe; a connection-level failure becomes "signature not met" instead of ending the
   * detection - unless the target validation rejected the address, which is the caller's problem to
   * fix and is rethrown with its allowlist hint intact.
   */
  private static ProbeOutcome probe(ConfluenceHttp http, String url, String resource)
      throws ConfluenceAccessException, InterruptedException {
    try {
      return new ProbeOutcome(http.get(url, resource), null);
    } catch (ConfluenceAccessException e) {
      if (e.getCause() instanceof TargetAddressValidator.TargetAddressBlockedException) {
        throw e;
      }
      return new ProbeOutcome(null, e);
    }
  }

  private static String hostRoot(URI base) {
    StringBuilder root = new StringBuilder(base.getScheme()).append("://").append(base.getHost());
    if (base.getPort() != -1) {
      root.append(':').append(base.getPort());
    }
    return root.toString();
  }

  private static boolean jsonHas(ConfluenceHttp.Response response, String field) {
    JsonNode node = parseQuietly(response);
    return node != null && node.isObject() && node.has(field);
  }

  private static boolean isJsonObject(ConfluenceHttp.Response response) {
    JsonNode node = parseQuietly(response);
    return node != null && node.isObject();
  }

  private static JsonNode parseQuietly(ConfluenceHttp.Response response) {
    try {
      return JSON.readTree(response.body());
    } catch (JacksonException e) {
      return null;
    }
  }
}
