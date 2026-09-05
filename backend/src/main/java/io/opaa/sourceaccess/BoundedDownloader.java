package io.opaa.sourceaccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a single file from a URL to a temporary local path, or a bounded byte range/stream.
 * Every request carries the {@link SourceRequestPolicy}'s {@code User-Agent}; the file and prefix
 * downloads wait out a {@code 429} under its {@link RateLimitPolicy}, the click-driven {@link
 * #downloadStreaming} never does. Every transfer is capped while it streams ({@link
 * BoundedStreams}), so no caller ever holds a whole body in memory or past its limit on disk.
 */
public class BoundedDownloader {

  private static final Logger log = LoggerFactory.getLogger(BoundedDownloader.class);

  /** Per-request timeout of an unattended background download. */
  static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

  private final TargetAddressValidator targetAddressValidator;
  private final SourceRequestPolicy requestPolicy;

  /** With {@link SourceRequestPolicy#defaults()}. */
  public BoundedDownloader(TargetAddressValidator targetAddressValidator) {
    this(targetAddressValidator, SourceRequestPolicy.defaults());
  }

  public BoundedDownloader(
      TargetAddressValidator targetAddressValidator, SourceRequestPolicy requestPolicy) {
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy = requestPolicy;
  }

  /**
   * Downloads {@code fileUrl} into a temp file <b>the caller must delete</b>, capped at {@code
   * maxBytes} while streaming, so one entry can never fill the temp partition. Follows an
   * off-origin redirect under {@link
   * RedirectFollowingFetcher.RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN}: an admin chose this
   * URL.
   *
   * @throws HttpStatusException on any status but {@code 200}
   * @throws AttachmentTooLargeException if the response body exceeds {@code maxBytes}; the partial
   *     temp file is deleted before it is thrown
   */
  public Path download(
      HttpClient httpClient, String authHeader, String fileUrl, String fileName, long maxBytes)
      throws IOException, InterruptedException {
    log.debug("Downloading: {}", fileUrl);
    return downloadToTempFile(
            httpClient,
            fileUrl,
            fileName,
            maxBytes,
            authHeader,
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN,
            RateLimitListener.NONE)
        .path();
  }

  /**
   * Reads at most {@code maxBytes} of {@code fileUrl}'s body for content detection alone, in
   * memory, never on disk, so a rejected listing entry normally costs only this bounded read -
   * except for an unresolved container, which {@code SupportedDocumentFormats#decideForPrefix} then
   * fetches in full. An accepted entry costs two requests, deliberately preferred over streaming
   * one connection through both phases.
   */
  public byte[] downloadPrefix(
      HttpClient httpClient, String authHeader, String fileUrl, int maxBytes)
      throws IOException, InterruptedException {

    log.debug("Downloading (bounded to {} bytes, for detection): {}", maxBytes, fileUrl);

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            fileUrl,
            DOWNLOAD_TIMEOUT,
            requestPolicy.headers(authHeader),
            targetAddressValidator,
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN,
            requestPolicy.rateLimitHandling());

    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        throw new HttpStatusException(response.statusCode(), fileUrl);
      }
      return body.readNBytes(maxBytes);
    }
  }

  /**
   * Downloads {@code fileUrl} into a temp file <b>the caller must delete</b>, capped at {@code
   * maxBytes} while streaming. Used for attachments a feed operator controls, so unlike {@link
   * #download} it follows a redirect only within {@code fileUrl}'s own origin ({@link
   * RedirectFollowingFetcher.RedirectPolicy#REJECT_OFF_ORIGIN}).
   *
   * @param authHeader the {@code Authorization} header value to send (e.g. {@code Basic ...}), or
   *     {@code null} to send none
   * @return the temp file alongside the response's declared {@code Content-Type}, which the
   *     Government Site Builder attachment profile needs to derive a file extension its URLs do not
   *     carry
   * @throws HttpStatusException on any status but {@code 200}
   * @throws AttachmentTooLargeException if the response body exceeds {@code maxBytes}
   * @throws RedirectFollowingFetcher.RedirectRejectedException if the request was redirected to a
   *     different origin than {@code fileUrl}'s own - scheme, host or normalized port. A protocol
   *     downgrade (https to http) is refused with the same exception even on an otherwise same-host
   *     redirect.
   */
  public DownloadedFile downloadBounded(
      HttpClient httpClient, String fileUrl, String fileName, long maxBytes, String authHeader)
      throws IOException, InterruptedException {
    return downloadBounded(
        httpClient,
        fileUrl,
        fileName,
        maxBytes,
        authHeader,
        RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN,
        RateLimitListener.NONE);
  }

  /**
   * {@link #downloadBounded(HttpClient, String, String, long, String)} with an explicit redirect
   * policy - {@link RedirectFollowingFetcher.RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN} for
   * sources that hand out content from a second, pre-signed host (Confluence Cloud's media
   * service), where refusing the hop would refuse every attachment - and the caller's own {@link
   * RateLimitListener}.
   */
  public DownloadedFile downloadBounded(
      HttpClient httpClient,
      String fileUrl,
      String fileName,
      long maxBytes,
      String authHeader,
      RedirectFollowingFetcher.RedirectPolicy redirectPolicy,
      RateLimitListener rateLimitListener)
      throws IOException, InterruptedException {
    log.debug("Downloading (bounded to {} bytes): {}", maxBytes, fileUrl);
    return downloadToTempFile(
        httpClient, fileUrl, fileName, maxBytes, authHeader, redirectPolicy, rateLimitListener);
  }

  /**
   * Streams {@code fileUrl} without ever buffering the full body, for a click-driven path a viewer
   * can trigger arbitrarily often, with a caller-supplied short {@code perRequestTimeout};
   * redirects are restricted as in {@link #downloadBounded}, a {@code 429} is not waited out. The
   * returned {@link DownloadedStream#stream()} is the live body, wrapped so a read past {@code
   * maxBytes} throws and closed by the caller; an over-large declared {@code Content-Length} is
   * rejected up front.
   */
  public DownloadedStream downloadStreaming(
      HttpClient httpClient,
      String fileUrl,
      long maxBytes,
      String authHeader,
      Duration perRequestTimeout)
      throws IOException, InterruptedException {
    log.debug("Streaming (bounded to {} bytes): {}", maxBytes, fileUrl);

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            fileUrl,
            perRequestTimeout,
            requestPolicy.headers(authHeader),
            targetAddressValidator,
            RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);

    if (response.statusCode() != 200) {
      closeQuietly(response.body());
      throw new HttpStatusException(response.statusCode(), fileUrl);
    }
    if (declaredLengthExceeds(response, maxBytes)) {
      closeQuietly(response.body());
      throw new AttachmentTooLargeException();
    }

    String contentType = response.headers().firstValue("Content-Type").orElse(null);
    InputStream bounded = BoundedStreams.input(response.body(), maxBytes);
    log.debug("Streaming {} (content-type {})", fileUrl, contentType);
    return new DownloadedStream(bounded, contentType);
  }

  /**
   * The one transfer to disk behind {@link #download} and {@link #downloadBounded}: a temp file
   * named after {@code fileName}'s extension, filled under {@code maxBytes} while streaming; on any
   * failure the partial file is deleted before the exception leaves.
   */
  private DownloadedFile downloadToTempFile(
      HttpClient httpClient,
      String fileUrl,
      String fileName,
      long maxBytes,
      String authHeader,
      RedirectFollowingFetcher.RedirectPolicy redirectPolicy,
      RateLimitListener rateLimitListener)
      throws IOException, InterruptedException {
    Map<String, String> headers = requestPolicy.headers(authHeader);
    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            fileUrl,
            DOWNLOAD_TIMEOUT,
            headers,
            targetAddressValidator,
            redirectPolicy,
            requestPolicy.rateLimitHandling(rateLimitListener));

    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        throw new HttpStatusException(response.statusCode(), fileUrl);
      }
      if (declaredLengthExceeds(response, maxBytes)) {
        throw new AttachmentTooLargeException();
      }
      // Preserve original extension for correct content-type detection
      Path tempFile = Files.createTempFile("opaa-", extractExtension(fileName));
      try (OutputStream out =
          Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING)) {
        BoundedStreams.copy(body, out, maxBytes);
      } catch (BoundedStreams.LimitExceededException e) {
        Files.deleteIfExists(tempFile);
        throw new AttachmentTooLargeException();
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(tempFile);
        throw e;
      }
      String contentType = response.headers().firstValue("Content-Type").orElse(null);
      log.debug("Downloaded {} to {}", fileUrl, tempFile);
      return new DownloadedFile(tempFile, contentType);
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
   * The result of {@link #downloadStreaming}: the still-open, bounded body and its declared type.
   */
  public record DownloadedStream(InputStream stream, String contentType) {}

  /**
   * Whether the response declares a {@code Content-Length} above {@code maxBytes}. An absent or
   * unparsable header answers {@code false} - the bounded copy still enforces the limit while
   * reading, regardless of what the header claimed.
   */
  private static boolean declaredLengthExceeds(HttpResponse<InputStream> response, long maxBytes) {
    Optional<String> declaredLength = response.headers().firstValue("Content-Length");
    if (declaredLength.isEmpty()) {
      return false;
    }
    try {
      return Long.parseLong(declaredLength.get()) > maxBytes;
    } catch (NumberFormatException e) {
      return false;
    }
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
   * A non-{@code 200} answer to a download - carries the status so a caller can tell a refused
   * ({@code 403}) or vanished ({@code 404}) attachment from an unreachable host.
   */
  public static final class HttpStatusException extends IOException {
    private final int statusCode;

    public HttpStatusException(int statusCode, String fileUrl) {
      super("HTTP " + statusCode + " downloading: " + fileUrl);
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  /**
   * Thrown by {@link #download}/{@link #downloadBounded}/{@link #downloadStreaming} when the
   * configured byte limit is exceeded while streaming.
   */
  public static final class AttachmentTooLargeException extends RuntimeException {}
}
