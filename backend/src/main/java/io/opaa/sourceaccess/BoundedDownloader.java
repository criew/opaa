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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads a single file from a URL to a temporary local path, or a bounded byte range/stream. */
public class BoundedDownloader {

  private static final Logger log = LoggerFactory.getLogger(BoundedDownloader.class);

  private final TargetAddressValidator targetAddressValidator;

  public BoundedDownloader(TargetAddressValidator targetAddressValidator) {
    this.targetAddressValidator = targetAddressValidator;
  }

  /**
   * Downloads {@code fileUrl} into a temp file <b>the caller must delete</b>, capped at {@code
   * maxBytes} while streaming, so one entry can never fill the temp partition. Follows an
   * off-origin redirect under {@link
   * RedirectFollowingFetcher.RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN}: an admin chose this
   * URL.
   *
   * @throws AttachmentTooLargeException if the response body exceeds {@code maxBytes}; the partial
   *     temp file is deleted before it is thrown
   */
  public Path download(
      HttpClient httpClient, String authHeader, String fileUrl, String fileName, long maxBytes)
      throws IOException, InterruptedException {

    log.debug("Downloading: {}", fileUrl);

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            fileUrl,
            Duration.ofSeconds(120),
            headers,
            targetAddressValidator,
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);

    Path tempFile;
    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
      }
      if (declaredLengthExceeds(response, maxBytes)) {
        throw new AttachmentTooLargeException();
      }
      // Preserve original extension for correct content-type detection
      tempFile = Files.createTempFile("opaa-", extractExtension(fileName));
      try (OutputStream out =
          Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING)) {
        copyBounded(body, out, maxBytes);
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(tempFile);
        throw e;
      }
    }

    log.debug("Downloaded {} to {}", fileUrl, tempFile);
    return tempFile;
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

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            fileUrl,
            Duration.ofSeconds(120),
            headers,
            targetAddressValidator,
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);

    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
      }
      return body.readNBytes(maxBytes);
    }
  }

  /**
   * Downloads {@code fileUrl} capped at {@code maxBytes} while streaming, so a remote end sending
   * past the limit is cut off before the bytes reach disk. Used for attachments a feed operator
   * controls, so unlike {@link #download} it follows a redirect only within {@code fileUrl}'s own
   * origin ({@link RedirectFollowingFetcher.RedirectPolicy#REJECT_OFF_ORIGIN}).
   *
   * @param userAgent the {@code User-Agent} header value to send, or {@code null} to send none
   * @param authHeader the {@code Authorization} header value to send (e.g. {@code Basic ...}), or
   *     {@code null} to send none - mirrors {@link #download}'s own {@code authHeader} parameter.
   * @return the temp file alongside the response's declared {@code Content-Type}, which the
   *     Government Site Builder attachment profile needs to derive a file extension its URLs do not
   *     carry
   * @throws AttachmentTooLargeException if the response body exceeds {@code maxBytes}
   * @throws RedirectFollowingFetcher.RedirectRejectedException if the request was redirected to a
   *     different origin than {@code fileUrl}'s own - scheme, host or normalized port. A protocol
   *     downgrade (https to http) is refused with the same exception even on an otherwise same-host
   *     redirect.
   */
  public DownloadedFile downloadBounded(
      HttpClient httpClient,
      String fileUrl,
      String fileName,
      long maxBytes,
      String userAgent,
      String authHeader)
      throws IOException, InterruptedException {
    return downloadBounded(
        httpClient,
        fileUrl,
        fileName,
        maxBytes,
        userAgent,
        authHeader,
        RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);
  }

  /**
   * {@link #downloadBounded(HttpClient, String, String, long, String, String)} with an explicit
   * redirect policy - {@link RedirectFollowingFetcher.RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN}
   * for sources that hand out content from a second, pre-signed host (Confluence Cloud's media
   * service), where refusing the hop would refuse every attachment.
   */
  public DownloadedFile downloadBounded(
      HttpClient httpClient,
      String fileUrl,
      String fileName,
      long maxBytes,
      String userAgent,
      String authHeader,
      RedirectFollowingFetcher.RedirectPolicy redirectPolicy)
      throws IOException, InterruptedException {
    log.debug("Downloading (bounded to {} bytes): {}", maxBytes, fileUrl);

    Map<String, String> headers = new LinkedHashMap<>();
    if (userAgent != null && !userAgent.isBlank()) {
      headers.put("User-Agent", userAgent);
    }
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            fileUrl,
            Duration.ofSeconds(120),
            headers,
            targetAddressValidator,
            redirectPolicy);

    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        throw new HttpStatusException(response.statusCode(), fileUrl);
      }

      byte[] bytes = readBounded(body, maxBytes);
      Path tempFile = Files.createTempFile("opaa-", extractExtension(fileName));
      Files.write(tempFile, bytes);

      String contentType = response.headers().firstValue("Content-Type").orElse(null);
      log.debug("Downloaded {} to {}", fileUrl, tempFile);
      return new DownloadedFile(tempFile, contentType);
    }
  }

  /**
   * Streams {@code fileUrl} without ever buffering the full body, for a click-driven path a viewer
   * can trigger arbitrarily often, with a caller-supplied short {@code perRequestTimeout};
   * redirects are restricted as in {@link #downloadBounded}. The returned {@link
   * DownloadedStream#stream()} is the live body, wrapped so a read past {@code maxBytes} throws and
   * closed by the caller; an over-large declared {@code Content-Length} is rejected up front.
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

    Map<String, String> headers = new LinkedHashMap<>();
    if (userAgent != null && !userAgent.isBlank()) {
      headers.put("User-Agent", userAgent);
    }
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            httpClient,
            fileUrl,
            perRequestTimeout,
            headers,
            targetAddressValidator,
            RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);

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
   * Copies {@code in} to {@code out}, throwing {@link AttachmentTooLargeException} the moment the
   * copied volume would exceed {@code maxBytes} - the excess is never written, and the caller is
   * responsible for deleting the partial target.
   */
  private static void copyBounded(InputStream in, OutputStream out, long maxBytes)
      throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > maxBytes) {
        throw new AttachmentTooLargeException();
      }
      out.write(buffer, 0, read);
    }
  }

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

  /**
   * Reads at most {@code maxBytes} from {@code in}, throwing {@link AttachmentTooLargeException}
   * the moment a further byte would exceed the limit - enforced while streaming.
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
   * Thrown by {@link #download}/{@link #downloadBounded}/{@link #downloadStreaming} when the
   * configured byte limit is exceeded while streaming.
   */
  /**
   * A non-{@code 200} answer to a bounded download - carries the status so a caller can tell a
   * refused ({@code 403}) or vanished ({@code 404}) attachment from an unreachable host. Message
   * shape unchanged from the plain {@code IOException} it replaces.
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

  public static final class AttachmentTooLargeException extends RuntimeException {}
}
