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
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads a single file from a URL to a temporary local path. */
public class UrlFileDownloader {

  private static final Logger log = LoggerFactory.getLogger(UrlFileDownloader.class);

  /**
   * Downloads a file from the given URL using the provided HTTP client and auth header. Returns the
   * path to a temporary file. The caller is responsible for deleting the temp file.
   */
  public Path download(HttpClient httpClient, String authHeader, String fileUrl, String fileName)
      throws IOException, InterruptedException {

    log.debug("Downloading: {}", fileUrl);

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder().uri(URI.create(fileUrl)).timeout(Duration.ofSeconds(120)).GET();

    if (authHeader != null) {
      reqBuilder.header("Authorization", authHeader);
    }

    // Preserve original extension for correct content-type detection
    String suffix = extractExtension(fileName);
    Path tempFile = Files.createTempFile("opaa-", suffix);

    HttpResponse<Path> response =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofFile(tempFile));

    if (response.statusCode() != 200) {
      Files.deleteIfExists(tempFile);
      throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
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
   * @return the temp file alongside the response's declared {@code Content-Type}, which the
   *     Government Site Builder attachment profile ({@link AttachmentProfile#GSB}) needs to derive
   *     a file extension its URLs do not carry (#468)
   * @throws AttachmentTooLargeException if the response body exceeds {@code maxBytes}
   * @throws ForeignHostRedirectException if the request was redirected to a different host than
   *     {@code fileUrl}'s own (PR #492 review, finding 4) - a same-host attachment link a profile
   *     already vetted must not silently end up downloading from, and being recorded as originating
   *     from, an address the profile never approved.
   */
  public DownloadedFile downloadBounded(
      HttpClient httpClient, String fileUrl, String fileName, long maxBytes, String userAgent)
      throws IOException, InterruptedException {
    log.debug("Downloading (bounded to {} bytes): {}", maxBytes, fileUrl);

    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder().uri(URI.create(fileUrl)).timeout(Duration.ofSeconds(120)).GET();
    if (userAgent != null && !userAgent.isBlank()) {
      requestBuilder.header("User-Agent", userAgent);
    }

    HttpResponse<InputStream> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

    try (InputStream body = response.body()) {
      if (isForeignHostRedirect(fileUrl, response.uri())) {
        throw new ForeignHostRedirectException("redirected to a foreign host: " + response.uri());
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

  /**
   * Whether {@code finalUri} landed on a different host than {@code originalUrl} - mirrors {@code
   * RssFeedIndexingExecutor#isForeignHostRedirect}'s treatment of detail-page redirects (PR #492
   * review, finding 4).
   */
  private static boolean isForeignHostRedirect(String originalUrl, URI finalUri) {
    try {
      URI originalUri = new URI(originalUrl);
      return originalUri.getHost() != null
          && finalUri.getHost() != null
          && !originalUri.getHost().equalsIgnoreCase(finalUri.getHost());
    } catch (URISyntaxException e) {
      return false;
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

  /** Thrown by {@link #downloadBounded} when the request was redirected to a foreign host. */
  public static final class ForeignHostRedirectException extends RuntimeException {
    ForeignHostRedirectException(String message) {
      super(message);
    }
  }
}
