package io.opaa.indexing.spi;

import io.opaa.compliance.DataFlow;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BasicCredentials;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams the original of a URL-fetched document from the source URL stored at indexing time - no
 * part of the request decides which URL is fetched. The target validation is applied again on every
 * hop, a redirect is only followed within the same origin, and the resolved credentials and TLS
 * switch apply as for a run; the credentials reach only the outbound {@code Authorization} header.
 *
 * <p>The stored URL is content the source operator controls (a feed entry's link), so the
 * credentials follow only a URL on the origin of the library's own {@code sourceUrl} ({@link
 * RedirectFollowingFetcher#sameOrigin}); a connector with a path scope applies its crawl's rule
 * instead. Any other original is fetched without them, and a redirect out of the scope drops them.
 *
 * <p>The body is bounded by {@link RemoteContentProperties#maxBytes()} while streaming, each hop by
 * {@link RemoteContentProperties#timeoutSeconds()} and the whole transfer by {@link
 * RemoteContentProperties#transferTimeoutSeconds()}: a reader waits on this click, not an
 * unattended run. Every failure - source offline, target refused, invalid stored configuration - is
 * "no original", never a 5xx that would suggest an OPAA-side error.
 */
@DataFlow("source-original")
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
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    String sourceUrl = document.getFilePath();
    boolean withCredentials = credentialsMayReachOrigin(settings.sourceUrl(), sourceUrl);
    return open(document, library, settings, withCredentials, RedirectFollowingFetcher.ANY_TARGET);
  }

  /**
   * {@link #openOriginal(Document, KnowledgeLibrary, SourceSettings)} under a crawl's rule, for
   * credentials bound to a path subtree: the stored URL must sit on a trusted origin of the
   * library's {@code sourceUrl} (the same one or its http-to-https upgrade, {@link
   * RedirectFollowingFetcher#isRedirectOriginTrusted}, as the crawl followed it) and inside {@code
   * credentialScope}, which also bounds every redirect target.
   */
  public Optional<DocumentContent> openOriginal(
      Document document,
      KnowledgeLibrary library,
      SourceSettings settings,
      Predicate<URI> credentialScope) {
    boolean withCredentials =
        credentialsMayReachSubtree(settings.sourceUrl(), document.getFilePath(), credentialScope);
    return open(document, library, settings, withCredentials, credentialScope);
  }

  private Optional<DocumentContent> open(
      Document document,
      KnowledgeLibrary library,
      SourceSettings settings,
      boolean withCredentials,
      Predicate<URI> credentialScope) {
    String sourceUrl = document.getFilePath();
    if (sourceUrl == null || sourceUrl.isBlank()) {
      return Optional.empty();
    }
    HttpClient httpClient = null;
    try {
      httpClient = SourceHttpClientFactory.buildHttpClient();
      String authHeader = null;
      if (withCredentials) {
        BasicCredentials config = BasicCredentials.parse(settings.sourceCredentials());
        authHeader = SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());
      }

      BoundedDownloader.DownloadedStream downloaded =
          boundedDownloader.downloadStreaming(
              httpClient,
              sourceUrl,
              properties.maxBytes(),
              authHeader,
              Duration.ofSeconds(properties.timeoutSeconds()),
              Duration.ofSeconds(properties.transferTimeoutSeconds()),
              credentialScope,
              library.loggedNames());

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
    } catch (BoundedDownloader.AttachmentTooLargeException | IOException e) {
      log.warn(
          "Remote document content unavailable: {} ({})",
          library.loggedNames().of(sourceUrl),
          library.loggedNames().of(e.getMessage()));
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

  /**
   * Whether the library's credentials may be sent to {@code target} under the strict rule: the same
   * origin as the library's {@code sourceUrl}. A missing or unparseable address on either side is
   * never trusted.
   */
  static boolean credentialsMayReachOrigin(String librarySourceUrl, String target) {
    return credentialsMayReach(
        librarySourceUrl, target, RedirectFollowingFetcher::sameOrigin, uri -> true);
  }

  /**
   * Whether the library's credentials may be sent to {@code target} under a crawl's rule: a trusted
   * origin of the library's {@code sourceUrl} ({@link
   * RedirectFollowingFetcher#isRedirectOriginTrusted}) and inside {@code credentialScope}.
   */
  static boolean credentialsMayReachSubtree(
      String librarySourceUrl, String target, Predicate<URI> credentialScope) {
    return credentialsMayReach(
        librarySourceUrl,
        target,
        RedirectFollowingFetcher::isRedirectOriginTrusted,
        credentialScope);
  }

  private static boolean credentialsMayReach(
      String librarySourceUrl,
      String target,
      BiPredicate<URI, URI> trustedOrigin,
      Predicate<URI> credentialScope) {
    if (librarySourceUrl == null || librarySourceUrl.isBlank() || target == null) {
      return false;
    }
    try {
      URI origin = URI.create(librarySourceUrl.strip());
      URI targetUri = URI.create(target.strip());
      return trustedOrigin.test(origin, targetUri) && credentialScope.test(targetUri);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static void closeQuietly(HttpClient httpClient) {
    if (httpClient != null) {
      httpClient.close();
    }
  }
}
