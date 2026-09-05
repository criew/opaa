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
 * Detects the edition behind an address without credentials (ADR-0023, Entscheidung 2): {@code GET
 * <host root>/_edge/tenant_info} identifies Cloud, which alone answers it with a {@code cloudId};
 * otherwise {@code GET <base>/status} or {@code GET <base>/rest/api/space?limit=1} identifies Data
 * Center. The Cloud content endpoints are unusable as a signature, since unauthenticated they
 * answer {@code 404} like any nonexistent path.
 *
 * <p>The host name is never consulted - either edition can sit behind any domain. A probe failing
 * at the connection level counts as "signature not met" and is named in the final message, except a
 * target-validation rejection, which surfaces as the configuration problem it is.
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
    ConfluenceHttp.validateProxy(targetAddressValidator, proxyHost);
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
        new ConfluenceHttp(
            client,
            cloud,
            properties,
            targetAddressValidator,
            sleeper,
            meter,
            properties.detectionTimeout());
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
        new ConfluenceHttp(
            client,
            dataCenter,
            properties,
            targetAddressValidator,
            sleeper,
            meter,
            properties.detectionTimeout());
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

  /**
   * Confirms one expected edition with the fewest probes - its own signature only (one request for
   * Cloud, one or two for Data Center). Used when a library is created: the stored edition must be
   * the instance's, but the instance was fully detected seconds earlier and need not be again.
   *
   * @return whether the instance answers with the expected edition's signature
   */
  public boolean confirms(
      String rawBaseUrl,
      String proxyHost,
      int proxyPort,
      boolean insecureSsl,
      ConfluenceEdition expected)
      throws ConfluenceAccessException, InterruptedException {
    ConfluenceHttp.validateProxy(targetAddressValidator, proxyHost);
    HttpClient client = SourceHttpClientFactory.buildHttpClient(proxyHost, proxyPort, insecureSsl);
    ConfluenceRequestMeter meter = new ConfluenceRequestMeter();
    String resource = "die Editionsprüfung";
    URI base = ConfluenceConnection.normalizeBaseUrl(rawBaseUrl, expected);
    ConfluenceConnection connection =
        new ConfluenceConnection(base, expected, null, proxyHost, proxyPort, insecureSsl);
    ConfluenceHttp http =
        new ConfluenceHttp(
            client,
            connection,
            properties,
            targetAddressValidator,
            sleeper,
            meter,
            properties.detectionTimeout());
    if (expected == ConfluenceEdition.CLOUD) {
      ProbeOutcome tenant = probe(http, hostRoot(base) + "/_edge/tenant_info", resource);
      if (tenant.failure != null) {
        throw tenant.failure;
      }
      return tenant.response.status() == 200 && jsonHas(tenant.response, "cloudId");
    }
    ProbeOutcome status = probe(http, connection.url("/status"), resource);
    if (status.response != null
        && status.response.status() == 200
        && jsonHas(status.response, "state")) {
      return true;
    }
    ProbeOutcome spaces =
        probe(http, connection.url(DataCenterConfluenceClient.REST + "/space?limit=1"), resource);
    if (spaces.failure != null && status.failure != null) {
      throw spaces.failure;
    }
    return spaces.response != null
        && (spaces.response.status() == 200 || spaces.response.status() == 401)
        && isJsonObject(spaces.response);
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
