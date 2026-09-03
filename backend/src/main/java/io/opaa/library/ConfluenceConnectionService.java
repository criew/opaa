package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.confluence.ConfluenceAccessException;
import io.opaa.indexing.source.confluence.ConfluenceClient;
import io.opaa.indexing.source.confluence.ConfluenceClientFactory;
import io.opaa.indexing.source.confluence.ConfluenceConnection;
import io.opaa.indexing.source.confluence.ConfluenceCredentials;
import io.opaa.indexing.source.confluence.ConfluenceEditionDetector;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The library-side face of the Confluence access layer (ADR-0023, #1134): detects the edition of an
 * address without credentials, verifies credentials and counts readable spaces for the connection
 * test, lists spaces for the selection, and re-checks the edition when a library is created. Every
 * outcome is a German, user-facing message without credentials; the access layer guarantees that
 * for its exceptions, this class only passes them on.
 */
@Service
public class ConfluenceConnectionService {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceConnectionService.class);

  private final ConfluenceClientFactory clientFactory;

  public ConfluenceConnectionService(ConfluenceClientFactory clientFactory) {
    this.clientFactory = clientFactory;
  }

  /** What the connection test needs to know about one Confluence instance. */
  public record Probe(
      boolean reachable,
      String message,
      ConfluenceEdition detectedEdition,
      boolean credentialsVerified,
      Long readableSpaces) {}

  /**
   * Stage one: detect the edition; stage two, if credentials are given: verify them against the
   * detected edition and count the readable spaces. Never throws for an instance problem - that is
   * the test's result - only for a caller problem ({@link ValidationException}).
   */
  public Probe probe(
      String rawUrl,
      String proxy,
      String credentials,
      boolean insecureSsl,
      ConfluenceEdition expectedEdition)
      throws InterruptedException {
    ProxyAndCredentials proxyConfig = parseProxy(proxy);
    ConfluenceEditionDetector.Detected detected;
    try {
      detected =
          clientFactory
              .editionDetector()
              .detect(rawUrl, proxyConfig.proxyHost(), proxyConfig.proxyPort(), insecureSsl);
    } catch (ConfluenceConnection.InvalidBaseUrlException e) {
      throw new ValidationException(e.getMessage());
    } catch (ConfluenceAccessException e) {
      return new Probe(false, e.getMessage(), null, false, null);
    }
    ConfluenceEdition edition = detected.edition();
    if (expectedEdition != null && expectedEdition != edition) {
      return new Probe(
          false,
          "Unter dieser Adresse antwortet Confluence "
              + label(edition)
              + ", nicht "
              + label(expectedEdition)
              + ". Die Zugangsdaten müssen zur erkannten Edition passen.",
          edition,
          false,
          null);
    }
    if (credentials == null || credentials.isBlank()) {
      return new Probe(
          true,
          "Confluence "
              + label(edition)
              + " erkannt. "
              + credentialsHint(edition)
              + " Danach werden die Zugangsdaten geprüft und die lesbaren Spaces ermittelt.",
          edition,
          false,
          null);
    }
    ConfluenceCredentials parsed;
    try {
      parsed = ConfluenceCredentials.parse(edition, credentials);
    } catch (ConfluenceCredentials.InvalidCredentialsFormatException e) {
      return new Probe(false, e.getMessage(), edition, false, null);
    }
    try {
      ConfluenceClient client =
          clientFactory.create(
              new ConfluenceConnection(
                  detected.baseUrl(),
                  edition,
                  parsed,
                  proxyConfig.proxyHost(),
                  proxyConfig.proxyPort(),
                  insecureSsl));
      // One authenticated request (ADR-0023, Entscheidung 2) - the readable spaces are listed by
      // the selection's own endpoint, never here, so a test can never turn into a full pagination.
      client.verifyCredentials();
      return new Probe(
          true,
          "Confluence "
              + label(edition)
              + " erreichbar, Zugangsdaten gültig. Die lesbaren Spaces stehen zur Auswahl.",
          edition,
          true,
          null);
    } catch (ConfluenceAccessException e) {
      return new Probe(false, e.getMessage(), edition, false, null);
    }
  }

  /** {@code sourceProxy} in {@code host:port}, or a 400 - never a 500 for a caller's typo. */
  private static ProxyAndCredentials parseProxy(String proxy) {
    try {
      return ProxyAndCredentials.parse(proxy, null);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  /**
   * Every space the credentials may read.
   *
   * @throws ValidationException with the instance's or the parser's German message when the listing
   *     cannot be produced - a 400 for the caller, never a stack trace
   */
  public List<ConfluenceSpace> listSpaces(
      String rawUrl,
      ConfluenceEdition edition,
      String proxy,
      String credentials,
      boolean insecureSsl)
      throws InterruptedException {
    if (edition == null) {
      throw new ValidationException("confluenceEdition ist erforderlich");
    }
    ProxyAndCredentials proxyConfig = parseProxy(proxy);
    URI baseUrl;
    ConfluenceCredentials parsed;
    try {
      baseUrl = ConfluenceConnection.normalizeBaseUrl(rawUrl, edition);
      parsed = ConfluenceCredentials.parse(edition, credentials);
    } catch (ConfluenceConnection.InvalidBaseUrlException
        | ConfluenceCredentials.InvalidCredentialsFormatException e) {
      throw new ValidationException(e.getMessage());
    }
    try {
      ConfluenceClient client =
          clientFactory.create(
              new ConfluenceConnection(
                  baseUrl,
                  edition,
                  parsed,
                  proxyConfig.proxyHost(),
                  proxyConfig.proxyPort(),
                  insecureSsl));
      return client.listSpaces();
    } catch (ConfluenceAccessException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  /**
   * Creation-time re-check (ADR-0023, Entscheidung 2): the stored edition must be the one the
   * instance actually is. Confirms the expected edition's own signature with the fewest probes (one
   * request for Cloud, one or two for Data Center, each bounded by the detection timeout) and runs
   * the full detection only to name the actual edition when the confirmation fails - so the
   * invariant never depends on what a client sent, at the cost of one short request per creation.
   *
   * @throws ValidationException naming the detected edition on a mismatch, or the instance problem
   *     when nothing could be detected
   */
  public void requireEdition(
      String normalizedUrl, String proxy, boolean insecureSsl, ConfluenceEdition expected) {
    ProxyAndCredentials proxyConfig = parseProxy(proxy);
    ConfluenceEditionDetector detector = clientFactory.editionDetector();
    try {
      if (detector.confirms(
          normalizedUrl, proxyConfig.proxyHost(), proxyConfig.proxyPort(), insecureSsl, expected)) {
        return;
      }
      ConfluenceEditionDetector.Detected detected =
          detector.detect(
              normalizedUrl, proxyConfig.proxyHost(), proxyConfig.proxyPort(), insecureSsl);
      log.info(
          "Refusing CONFLUENCE library: instance is {} but {} was requested",
          detected.edition(),
          expected);
      throw new ValidationException(
          "Unter dieser Adresse antwortet Confluence "
              + label(detected.edition())
              + ", nicht "
              + label(expected)
              + "; die Edition wird erkannt, nicht gewählt.");
    } catch (ConfluenceAccessException e) {
      throw new ValidationException(
          "Die Confluence-Edition konnte nicht bestätigt werden: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Prüfung der Confluence-Edition wurde unterbrochen.");
    }
  }

  static String label(ConfluenceEdition edition) {
    return switch (edition) {
      case CLOUD -> "Cloud";
      case DATA_CENTER -> "Data Center";
    };
  }

  private static String credentialsHint(ConfluenceEdition edition) {
    return switch (edition) {
      case CLOUD -> "Geben Sie E-Mail-Adresse und API-Token des Dienstkontos ein.";
      case DATA_CENTER -> "Geben Sie das Personal Access Token des Dienstkontos ein.";
    };
  }
}
