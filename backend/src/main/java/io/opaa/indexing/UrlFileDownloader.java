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

    // Authorization (built from the source configuration's own credentials) must not be
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
   * Reads at most {@code maxBytes} of {@code fileUrl}'s response body, for content detection alone
   * - never written to disk, held entirely in memory since {@link
   * SupportedDocumentFormats#detectMediaType(byte[])} needs only a bounded sample. {@link
   * UrlIndexingExecutor} calls this before {@link #download}, so a directory listing entry this
   * system ends up rejecting costs a bounded read, never the full transfer {@link #download}
   * performs. Follows redirects exactly like {@link #download} via the same {@link
   * AutoindexCrawlerService#sendFollowingRedirects}.
   *
   * <p>Costs a second request for every entry this system ends up indexing (one bounded read here,
   * one full transfer via {@link #download} once accepted) - accepted deliberately in favour of the
   * simpler two-step shape over streaming a single connection through both phases.
   */
  public byte[] downloadPrefix(
      HttpClient httpClient, String authHeader, String fileUrl, int maxBytes)
      throws IOException, InterruptedException {

    log.debug("Downloading (bounded to {} bytes, for detection): {}", maxBytes, fileUrl);

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    HttpResponse<InputStream> response =
        AutoindexCrawlerService.sendFollowingRedirects(
            httpClient, fileUrl, Duration.ofSeconds(120), headers, targetAddressValidator);

    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
      }
      return body.readNBytes(maxBytes);
    }
  }

  /**
   * Downloads a file from {@code fileUrl}, capped at {@code maxBytes} while streaming - the
   * response body is read in a bounded chunk rather than handed straight to {@link
   * HttpResponse.BodyHandlers#ofFile}, so a remote end that keeps sending past the configured limit
   * is cut off before the bytes ever reach disk. Mirrors how {@link RssFeedIndexingExecutor}
   * already bounds the feed itself and every detail page it fetches.
   *
   * <p>Used for RSS entry attachments, whose remote end (like a detail page's) is a feed operator
   * OPAA does not control - unlike {@link #download}, used for {@code HTTP_DIRECTORY} crawls of an
   * address the system administration chose deliberately.
   *
   * @param userAgent the {@code User-Agent} header value to send, or {@code null} to send none
   * @param authHeader the {@code Authorization} header value to send (e.g. {@code Basic ...}), or
   *     {@code null} to send none - mirrors {@link #download}'s own {@code authHeader} parameter.
   *     Never resent past a foreign-host redirect: {@link ForeignHostRedirectException} is thrown
   *     before a request for that hop is ever built.
   * @return the temp file alongside the response's declared {@code Content-Type}, which the
   *     Government Site Builder attachment profile ({@link AttachmentProfile#GSB}) needs to derive
   *     a file extension its URLs do not carry
   * @throws AttachmentTooLargeException if the response body exceeds {@code maxBytes}
   * @throws ForeignHostRedirectException if the request was redirected to a different origin than
   *     {@code fileUrl}'s own - scheme, host or normalized port. A protocol downgrade (https to
   *     http) is refused with the same exception even on an otherwise same-host redirect.
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

        // Covers the production client (Redirect.NEVER, AutoindexCrawlerService#buildHttpClient) -
        // the response is the raw 3xx here, never auto-followed, so the redirect target is
        // resolved and vetted manually before a single further byte is ever requested.
        if (AutoindexCrawlerService.isRedirectStatus(response.statusCode())) {
          Optional<String> location = response.headers().firstValue("Location");
          if (location.isEmpty() || hop >= AutoindexCrawlerService.MAX_REDIRECTS) {
            throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
          }
          URI redirectUri = currentUri.resolve(location.get());
          // A protocol downgrade is refused outright - see
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
   * Streams {@code fileUrl} without ever buffering the full response body in heap or on disk - the
   * counterpart to {@link #downloadBounded} for a caller-facing, synchronous, click-driven path
   * ({@code LibraryDocumentService#loadRemoteContent}) rather than a background indexing run:
   * {@code downloadBounded} reads the entire response into a {@code byte[]} up to {@code maxBytes}
   * before returning, which - unlike a single indexing run - a viewer can trigger arbitrarily often
   * and in parallel. Redirects are followed the same way {@link #downloadBounded} does, and {@code
   * perRequestTimeout} is a caller-supplied, deliberately short timeout instead of {@link
   * #downloadBounded}'s fixed 120s background-run timeout.
   *
   * <p>The returned {@link DownloadedStream#stream()} is the live, still-open HTTP response body,
   * wrapped so that a further read past {@code maxBytes} throws {@link IOException} instead of
   * silently continuing - the caller is responsible for closing it. When the response declares a
   * {@code Content-Length} larger than {@code maxBytes} up front, this method rejects the request
   * before returning at all ({@link AttachmentTooLargeException}); a source that omits or
   * understates {@code Content-Length} is instead caught by the bounded stream once the body is
   * actually read past the limit.
   */
  public DownloadedStream downloadStreaming(
      HttpClient httpClient,
      String fileUrl,
      long maxBytes,
      String userAgent,
      String authHeader,
      Duration perRequestTimeout)
      throws IOException, InterruptedException {
    log.debug("Streaming (bounded to {} bytes): {}", maxBytes, fileUrl);

    URI currentUri = URI.create(fileUrl);
    for (int hop = 0; ; hop++) {
      targetAddressValidator.validate(currentUri);
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder().uri(currentUri).timeout(perRequestTimeout).GET();
      if (userAgent != null && !userAgent.isBlank()) {
        requestBuilder.header("User-Agent", userAgent);
      }
      if (authHeader != null) {
        requestBuilder.header("Authorization", authHeader);
      }

      HttpResponse<InputStream> response =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

      if (isForeignHostRedirect(fileUrl, response.uri())) {
        closeQuietly(response.body());
        throw new ForeignHostRedirectException(
            "redirected to a foreign host: " + response.uri(),
            AutoindexCrawlerService.redirectRejectionMessage(
                AutoindexCrawlerService.RedirectRejectionReason.FOREIGN_HOST, response.uri()));
      }

      if (AutoindexCrawlerService.isRedirectStatus(response.statusCode())) {
        closeQuietly(response.body());
        Optional<String> location = response.headers().firstValue("Location");
        if (location.isEmpty() || hop >= AutoindexCrawlerService.MAX_REDIRECTS) {
          throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
        }
        URI redirectUri = currentUri.resolve(location.get());
        if (AutoindexCrawlerService.isSchemeDowngrade(currentUri, redirectUri)) {
          throw new ForeignHostRedirectException(
              "refusing a protocol downgrade redirect (https to http): " + redirectUri,
              AutoindexCrawlerService.redirectRejectionMessage(
                  AutoindexCrawlerService.RedirectRejectionReason.PROTOCOL_DOWNGRADE, redirectUri));
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
        closeQuietly(response.body());
        throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
      }

      Optional<String> declaredLength = response.headers().firstValue("Content-Length");
      if (declaredLength.isPresent()) {
        try {
          if (Long.parseLong(declaredLength.get()) > maxBytes) {
            closeQuietly(response.body());
            throw new AttachmentTooLargeException();
          }
        } catch (NumberFormatException e) {
          // Not a valid Content-Length - fall through to the bounded stream below, which still
          // enforces the limit while reading regardless of what the header claimed.
        }
      }

      String contentType = response.headers().firstValue("Content-Type").orElse(null);
      InputStream bounded = new BoundedInputStream(response.body(), maxBytes);
      log.debug("Streaming {} (content-type {})", fileUrl, contentType);
      return new DownloadedStream(bounded, contentType);
    }
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      log.debug("Failed to close response body while rejecting a candidate hop", e);
    }
  }

  /**
   * Enforces {@code maxBytes} while the underlying stream is actually read, rather than up front -
   * a further read past the limit throws {@link IOException}, which - once headers have already
   * been written to the caller - simply aborts the response rather than changing its status.
   */
  private static final class BoundedInputStream extends java.io.FilterInputStream {
    private final long maxBytes;
    private long bytesRead;

    BoundedInputStream(InputStream in, long maxBytes) {
      super(in);
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b != -1) {
        bytesRead++;
        checkLimit();
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        bytesRead += n;
        checkLimit();
      }
      return n;
    }

    private void checkLimit() throws IOException {
      if (bytesRead > maxBytes) {
        throw new IOException("Remote response exceeded the configured size limit");
      }
    }
  }

  /**
   * The result of {@link #downloadStreaming}: the still-open, bounded body and its declared type.
   */
  public record DownloadedStream(InputStream stream, String contentType) {}

  /**
   * Whether {@code finalUri} is a different origin than {@code originalUrl} (scheme, host and
   * normalized port - {@link AutoindexCrawlerService#sameOrigin}) - mirrors {@code
   * RssFeedIndexingExecutor#isForeignHostRedirect}'s treatment of detail-page redirects. An
   * unparsable host on either side, or an unparsable {@code originalUrl}, is always treated as
   * foreign. Delegates to {@link AutoindexCrawlerService#isRedirectOriginTrusted} rather than
   * {@code sameOrigin} directly, so a same-host http→https upgrade is not treated as foreign.
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
   * AutoindexCrawlerService#redirectRejectionMessage} - while this exception's own message stays
   * the unsanitized, developer-facing detail for the log only.
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
