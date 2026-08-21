package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads a single file from a URL to a temporary local path. */
public class UrlFileDownloader {

  private static final Logger log = LoggerFactory.getLogger(UrlFileDownloader.class);

  private final TargetAddressValidator targetAddressValidator;

  public UrlFileDownloader(TargetAddressValidator targetAddressValidator) {
    this.targetAddressValidator = targetAddressValidator;
  }

  /**
   * Downloads a file from the given URL using the provided HTTP client and auth header. Returns the
   * path to a temporary file. The caller is responsible for deleting the temp file.
   */
  public Path download(HttpClient httpClient, String authHeader, String fileUrl, String fileName)
      throws IOException, InterruptedException {

    log.debug("Downloading: {}", fileUrl);

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    // #538: Authorization (built from the source configuration's own credentials) must not be
    // replayed to a redirect target on a different host/scheme - see
    // AutoindexCrawlerService.sendFollowingRedirects's Javadoc.
    HttpResponse<InputStream> response =
        AutoindexCrawlerService.sendFollowingRedirects(
            httpClient, fileUrl, Duration.ofSeconds(120), headers, targetAddressValidator);

    // Preserve original extension for correct content-type detection
    String suffix = extractExtension(fileName);
    Path tempFile = Files.createTempFile("opaa-", suffix);

    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        Files.deleteIfExists(tempFile);
        throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
      }
      Files.copy(body, tempFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(tempFile);
      throw e;
    }

    log.debug("Downloaded {} to {}", fileUrl, tempFile);
    return tempFile;
  }

  /**
   * Downloads a file from {@code fileUrl}, capped at {@code maxBytes} while streaming (#468) - the
   * response body is read in a bounded chunk rather than handed straight to {@link
   * HttpResponse.BodyHandlers#ofFile}, so a remote end that keeps sending past the configured limit
   * is cut off before the bytes ever reach disk. Mirrors how {@link RssFeedIndexingExecutor}
   * already bounds the feed itself and every detail page it fetches.
   *
   * <p>Used for RSS entry attachments, whose remote end (like a detail page's) is a feed operator
   * OPAA does not control - unlike {@link #download}, used for {@code HTTP_DIRECTORY} crawls of an
   * address the system administration chose deliberately.
   *
   * @param userAgent the {@code User-Agent} header value to send, or {@code null} to send none - PR
   *     #492 review, finding 6: {@link RssFeedIndexingExecutor} already sends its configured,
   *     truthful {@code User-Agent} for the feed and every detail page; an attachment request left
   *     it out entirely.
   * @param authHeader the {@code Authorization} header value to send (e.g. {@code Basic ...}), or
   *     {@code null} to send none (#505) - mirrors {@link #download}'s own {@code authHeader}
   *     parameter. Never resent past a foreign-host redirect: {@link ForeignHostRedirectException}
   *     is thrown before a request for that hop is ever built, exactly as it already was before
   *     this parameter existed.
   * @return the temp file alongside the response's declared {@code Content-Type}, which the
   *     Government Site Builder attachment profile ({@link AttachmentProfile#GSB}) needs to derive
   *     a file extension its URLs do not carry (#468)
   * @throws AttachmentTooLargeException if the response body exceeds {@code maxBytes}
   * @throws ForeignHostRedirectException if the request was redirected to a different origin than
   *     {@code fileUrl}'s own - scheme, host or normalized port (PR #492 review, finding 4; port
   *     added in the #538 follow-up review) - a same-origin attachment link a profile already
   *     vetted must not silently end up downloading from, and being recorded as originating from,
   *     an address the profile never approved. A protocol downgrade (https to http) is refused with
   *     the same exception even on an otherwise same-host redirect.
   */
  public DownloadedFile downloadBounded(
      HttpClient httpClient,
      String fileUrl,
      String fileName,
      long maxBytes,
      String userAgent,
      String authHeader)
      throws IOException, InterruptedException {
    log.debug("Downloading (bounded to {} bytes): {}", maxBytes, fileUrl);

    URI currentUri = URI.create(fileUrl);
    for (int hop = 0; ; hop++) {
      targetAddressValidator.validate(currentUri);
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder().uri(currentUri).timeout(Duration.ofSeconds(120)).GET();
      if (userAgent != null && !userAgent.isBlank()) {
        requestBuilder.header("User-Agent", userAgent);
      }
      if (authHeader != null) {
        requestBuilder.header("Authorization", authHeader);
      }

      HttpResponse<InputStream> response =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

      try (InputStream body = response.body()) {
        // Covers a client that already followed the redirect itself (e.g. Redirect.NORMAL) -
        // response.uri() then already reflects the followed target.
        if (isForeignHostRedirect(fileUrl, response.uri())) {
          throw new ForeignHostRedirectException(
              "redirected to a foreign host: " + response.uri(),
              AutoindexCrawlerService.redirectRejectionMessage(
                  AutoindexCrawlerService.RedirectRejectionReason.FOREIGN_HOST, response.uri()));
        }

        // #538: covers the production client (Redirect.NEVER,
        // AutoindexCrawlerService#buildHttpClient)
        // - the response is the raw 3xx here, never auto-followed, so the redirect target is
        // resolved and vetted manually before a single further byte is ever requested.
        if (AutoindexCrawlerService.isRedirectStatus(response.statusCode())) {
          Optional<String> location = response.headers().firstValue("Location");
          if (location.isEmpty() || hop >= AutoindexCrawlerService.MAX_REDIRECTS) {
            throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
          }
          URI redirectUri = currentUri.resolve(location.get());
          // #538 follow-up review: a protocol downgrade is refused outright, the one thing
          // Redirect.NORMAL itself always refused too - see
          // AutoindexCrawlerService.isSchemeDowngrade's Javadoc.
          if (AutoindexCrawlerService.isSchemeDowngrade(currentUri, redirectUri)) {
            throw new ForeignHostRedirectException(
                "refusing a protocol downgrade redirect (https to http): " + redirectUri,
                AutoindexCrawlerService.redirectRejectionMessage(
                    AutoindexCrawlerService.RedirectRejectionReason.PROTOCOL_DOWNGRADE,
                    redirectUri));
          }
          if (isForeignHostRedirect(currentUri.toString(), redirectUri)) {
            throw new ForeignHostRedirectException(
                "redirected to a foreign host: " + redirectUri,
                AutoindexCrawlerService.redirectRejectionMessage(
                    AutoindexCrawlerService.RedirectRejectionReason.FOREIGN_HOST, redirectUri));
          }
          currentUri = redirectUri;
          continue;
        }

        if (response.statusCode() != 200) {
          throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
        }

        byte[] bytes = readBounded(body, maxBytes);
        Path tempFile = Files.createTempFile("opaa-", extractExtension(fileName));
        Files.write(tempFile, bytes);

        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        log.debug("Downloaded {} to {}", fileUrl, tempFile);
        return new DownloadedFile(tempFile, contentType);
      }
    }
  }

  /**
   * Whether {@code finalUri} is a different origin than {@code originalUrl} (scheme, host and
   * normalized port - {@link AutoindexCrawlerService#sameOrigin}, #538 follow-up review closing the
   * port gap a host-only comparison originally left open) - mirrors {@code
   * RssFeedIndexingExecutor#isForeignHostRedirect}'s treatment of detail-page redirects (PR #492
   * review, finding 4).
   *
   * <p><b>An unparsable host on either side is foreign, not "not foreign" (#651).</b> Delegates the
   * comparison entirely to {@link AutoindexCrawlerService#sameOrigin} instead of special-casing
   * {@code getHost() == null} to {@code false} ("not foreign") - see {@code
   * RssFeedIndexingExecutor#isForeignHostRedirect}'s Javadoc for why that inverted {@code
   * sameOrigin}'s own reasoning (#615 review, finding 1).
   *
   * <p><b>An unparsable {@code originalUrl} is foreign too (PR #664 review, finding 2).</b> Mirrors
   * {@code RssFeedIndexingExecutor#isForeignHostRedirect}'s identical fix: {@code originalUrl} is
   * always {@code fileUrl} or an already-followed, previously-vetted redirect hop here, so a {@code
   * new URI(...)} failure at this point has no legitimate cause - falling back to {@code false}
   * would reintroduce the same inverted "unparsable = trusted" default the null-host fix removes.
   *
   * <p><b>A same-host http→https upgrade is not foreign (#693).</b> Delegates to {@link
   * AutoindexCrawlerService#isRedirectOriginTrusted} rather than {@code sameOrigin} directly - see
   * that method's Javadoc: the ubiquitous upgrade redirect a well-behaved http:// source's server
   * sends was, before this fix, indistinguishable from a genuine cross-origin redirect and rejected
   * outright, breaking every attachment on such a source.
   */
  private static boolean isForeignHostRedirect(String originalUrl, URI finalUri) {
    try {
      URI originalUri = new URI(originalUrl);
      return !AutoindexCrawlerService.isRedirectOriginTrusted(originalUri, finalUri);
    } catch (URISyntaxException e) {
      return true;
    }
  }

  /**
   * Reads at most {@code maxBytes} from {@code in}, throwing {@link AttachmentTooLargeException}
   * the moment a further byte would exceed the limit - enforced while streaming, mirroring {@code
   * RssFeedIndexingExecutor#readBounded}'s treatment of the feed and its detail pages.
   */
  private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
    byte[] probe = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
    if (probe.length > maxBytes) {
      throw new AttachmentTooLargeException();
    }
    return probe;
  }

  private String extractExtension(String fileName) {
    if (fileName == null) {
      return ".tmp";
    }
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex >= 0) {
      return fileName.substring(dotIndex);
    }
    return ".tmp";
  }

  /** The result of {@link #downloadBounded}: the downloaded temp file and its declared type. */
  public record DownloadedFile(Path path, String contentType) {}

  /**
   * Thrown by {@link #downloadBounded} when the configured byte limit is exceeded while streaming.
   */
  public static final class AttachmentTooLargeException extends RuntimeException {}

  /**
   * Thrown by {@link #downloadBounded} when the request was redirected to a foreign host, or
   * refused a protocol downgrade. {@link #userMessage()} is a German, user-facing text distinct per
   * cause and stripped of path/query/fragment - see {@link
   * AutoindexCrawlerService#redirectRejectionMessage} - while this exception's own ({@code super})
   * message stays the unsanitized, developer-facing detail for the log only (maintainer nachtrag to
   * #693, 21.08.2026).
   */
  public static final class ForeignHostRedirectException extends RuntimeException {
    private final String userMessage;

    ForeignHostRedirectException(String logMessage, String userMessage) {
      super(logMessage);
      this.userMessage = userMessage;
    }

    public String userMessage() {
      return userMessage;
    }
  }
}
