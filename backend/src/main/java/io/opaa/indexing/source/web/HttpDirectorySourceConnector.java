package io.opaa.indexing.source.web;

import static io.opaa.indexing.source.ConnectorChecks.REQUEST_TIMEOUT;
import static io.opaa.indexing.source.ConnectorChecks.parseProxyAndCredentials;
import static io.opaa.indexing.source.ConnectorChecks.reachable;
import static io.opaa.indexing.source.ConnectorChecks.requireHttpUrl;
import static io.opaa.indexing.source.ConnectorChecks.translateConnectionError;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.source.ConnectorChecks;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.RateLimitHandling;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HTTP directory listing. The connection test uses the run's own building blocks - {@link
 * SourceHttpClientFactory}, the shared {@link SourceRequestPolicy} headers, {@link
 * RedirectFollowingFetcher} with the target validation on every hop - and reads the listing page
 * bounded by {@code opaa.indexing.rss.max-page-size-bytes}. It looks at the top level only and
 * counts by file name; the run itself decides from each downloaded file.
 */
public class HttpDirectorySourceConnector implements SourceConnector {

  private static final Logger log = LoggerFactory.getLogger(HttpDirectorySourceConnector.class);

  private static final SourceConnectorDescriptor DESCRIPTOR =
      SourceConnectorDescriptor.runBased(DocumentSourceType.HTTP_DIRECTORY);

  private final AutoindexCrawlerService crawlerService;
  private final TargetAddressValidator targetAddressValidator;
  private final SourceRequestPolicy requestPolicy;
  private final SupportedDocumentFormats supportedFormats;
  private final long maxPageSizeBytes;

  public HttpDirectorySourceConnector(
      AutoindexCrawlerService crawlerService,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      SupportedDocumentFormats supportedFormats,
      IndexingProperties properties) {
    this.crawlerService = crawlerService;
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy = requestPolicy;
    this.supportedFormats = supportedFormats;
    this.maxPageSizeBytes = properties.rss().maxPageSizeBytes();
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    ConnectorChecks.validateUrlBasedConfiguration(
        DocumentSourceType.HTTP_DIRECTORY, requested.sourcePath(), requested.sourceUrl());
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings) {
    String url = requireHttpUrl(DocumentSourceType.HTTP_DIRECTORY, settings);
    // the run's own rule: a slash is appended unless the address already names a file, so a
    // working ".../index.html" is not turned into a false 404
    if (!url.endsWith("/") && !UrlIndexingExecutor.hasFileExtension(url)) {
      url = url + "/";
    }
    ProxyAndCredentials config = parseProxyAndCredentials(settings);
    HttpClient httpClient =
        SourceHttpClientFactory.buildHttpClient(
            config.proxyHost(), config.proxyPort(), settings.sourceInsecureSsl());
    String authHeader =
        SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());
    Map<String, String> headers = requestPolicy.headers(authHeader);

    try {
      // the proxy decides where the connection goes and is validated like the target itself
      targetAddressValidator.validateHost(config.proxyHost());
      // Authorization is not replayed to a redirect off the tested URL's origin or subtree
      HttpResponse<InputStream> response =
          RedirectFollowingFetcher.sendFollowingRedirects(
              httpClient,
              url,
              REQUEST_TIMEOUT,
              headers,
              targetAddressValidator,
              RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN,
              RateLimitHandling.NONE,
              AutoindexCrawlerService.credentialScope(url));
      try (InputStream body = response.body()) {
        // Judged before the status: a run never reads such a page, whatever it answers.
        if (!AutoindexCrawlerService.staysInsideStartSubtree(url, response.uri())) {
          return unreachable(
              "Die Adresse leitet auf eine Adresse außerhalb der Start-URL weiter (Ziel: "
                  + RedirectFollowingFetcher.sanitizedOrigin(response.uri())
                  + "); ein Lauf würde diese Seite nicht auswerten.");
        }
        if (response.statusCode() == 401) {
          return unreachable(
              "Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).");
        }
        if (response.statusCode() == 403) {
          return unreachable("Der Zugriff wurde vom Server verweigert (HTTP 403 Forbidden).");
        }
        if (response.statusCode() == 404) {
          return unreachable("Die Adresse wurde auf dem Server nicht gefunden (HTTP 404).");
        }
        if (response.statusCode() != 200) {
          return unreachable("Der Server antwortete mit HTTP " + response.statusCode() + ".");
        }
        byte[] bytes;
        try {
          bytes = BoundedStreams.readFully(body, maxPageSizeBytes);
        } catch (BoundedStreams.LimitExceededException e) {
          return unreachable(
              "Die Verzeichnisseite überschreitet die zulässige Größe von "
                  + maxPageSizeBytes
                  + " Byte.");
        }
        String html = new String(bytes, StandardCharsets.UTF_8);
        List<AutoindexCrawlerService.CrawledFileEntry> entries =
            crawlerService.parseTopLevelEntries(html, url);
        long linkedDocuments =
            entries.stream()
                .filter(e -> !e.isDirectory())
                .filter(e -> supportedFormats.isSupported(e.name()))
                .count();
        // an empty result from a page that is no recognisable listing (a login or error page) is
        // reported as such; a recognised but empty listing still counts as a working source
        if (linkedDocuments == 0 && !crawlerService.looksLikeDirectoryListing(html)) {
          return unreachable(
              "Die Adresse antwortet, liefert aber kein erkennbares Verzeichnislisting.");
        }
        return reachable(
            "Webverzeichnis erreichbar, "
                + supportedDocumentPhrase(linkedDocuments)
                + " auf oberster Ebene gefunden.",
            linkedDocuments);
      }
    } catch (IOException e) {
      log.warn("HTTP_DIRECTORY source test failed for {}: {}", url, e.getMessage());
      return unreachable(translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Die Verbindung wurde unterbrochen.");
    }
  }

  /** "1 unterstütztes Dokument", "N unterstützte Dokumente" - adjective and noun agree. */
  private static String supportedDocumentPhrase(long count) {
    return count
        + " "
        + (count == 1 ? "unterstütztes" : "unterstützte")
        + " "
        + (count == 1 ? "Dokument" : "Dokumente");
  }
}
