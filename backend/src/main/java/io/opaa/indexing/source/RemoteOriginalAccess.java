package io.opaa.indexing.source;

import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams the original of a URL-fetched document from the source URL stored at indexing time - no
 * part of the request decides which URL is fetched. The target validation is applied again on every
 * hop and to the proxy, a redirect is only followed within the same origin, and the library's own
 * proxy, credentials and TLS switch apply as for a run; the credentials reach only the outbound
 * {@code Authorization} header.
 *
 * <p>The body is bounded by {@link RemoteContentProperties#maxBytes()} while streaming and each hop
 * by its own short timeout: a reader waits on this click, not an unattended run. Every failure -
 * source offline, target refused, invalid stored configuration - is "no original", never a 5xx that
 * would suggest an OPAA-side error.
 */
public class RemoteOriginalAccess implements OriginalAccess {

  private static final Logger log = LoggerFactory.getLogger(RemoteOriginalAccess.class);

  private final BoundedDownloader boundedDownloader;
  private final TargetAddressValidator targetAddressValidator;
  private final RemoteContentProperties properties;

  public RemoteOriginalAccess(
      BoundedDownloader boundedDownloader,
      TargetAddressValidator targetAddressValidator,
      RemoteContentProperties properties) {
    this.boundedDownloader = boundedDownloader;
    this.targetAddressValidator = targetAddressValidator;
    this.properties = properties;
  }

  @Override
  public Optional<DocumentContent> openOriginal(Document document, KnowledgeLibrary library) {
    String sourceUrl = document.getFilePath();
    if (sourceUrl == null || sourceUrl.isBlank()) {
      return Optional.empty();
    }
    HttpClient httpClient = null;
    try {
      ProxyAndCredentials config =
          ProxyAndCredentials.parse(library.getSourceProxy(), library.getSourceCredentials());
      httpClient =
          SourceHttpClientFactory.buildHttpClient(
              config.proxyHost(), config.proxyPort(), library.isSourceInsecureSsl());
      // the proxy decides where the connection (and Authorization below) goes
      targetAddressValidator.validateHost(config.proxyHost());
      String authHeader =
          SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());

      BoundedDownloader.DownloadedStream downloaded =
          boundedDownloader.downloadStreaming(
              httpClient,
              sourceUrl,
              properties.maxBytes(),
              authHeader,
              Duration.ofSeconds(properties.timeoutSeconds()));

      // the type decided at index time comes first; the declared one is only a fallback
      String contentType = document.getContentType();
      if (contentType == null || contentType.isBlank()) {
        contentType = ServedOriginals.normalizeContentType(downloaded.contentType());
      }
      if (contentType == null || contentType.isBlank()) {
        contentType = "application/octet-stream";
      }
      // closing the served stream also closes the per-request client
      HttpClient clientToClose = httpClient;
      InputStream closingStream =
          new FilterInputStream(downloaded.stream()) {
            @Override
            public void close() throws IOException {
              try {
                super.close();
              } finally {
                clientToClose.close();
              }
            }
          };
      return Optional.of(
          DocumentContent.ofStream(closingStream, document.getFileName(), contentType));
    } catch (BoundedDownloader.AttachmentTooLargeException
        | ProxyAndCredentials.InvalidProxyConfigurationException
        | IOException e) {
      log.warn("Remote document content unavailable: {} ({})", sourceUrl, e.getMessage());
      closeQuietly(httpClient);
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      closeQuietly(httpClient);
      return Optional.empty();
    }
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return OptionalLong.of(properties.maxBytes());
  }

  private static void closeQuietly(HttpClient httpClient) {
    if (httpClient != null) {
      httpClient.close();
    }
  }
}
