package io.opaa.indexing.source.web;

import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.source.attachment.AttachmentCandidate;
import io.opaa.indexing.source.rss.RssFeedIndexingExecutor;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Fetches a single RSS entry's detail page and reduces it to its main content's text plus
 * attachment candidates, split out of {@link RssFeedIndexingExecutor}. Package-private - an
 * implementation detail of the executor, not a new public API.
 *
 * <p>{@code nav}/{@code header}/{@code footer}/menu-ish elements are stripped before the configured
 * {@link IndexingProperties.Rss#mainContentSelector()} is applied, so boilerplate inside the
 * matched main element does not survive either and is never considered for attachments.
 *
 * <p>Public - consumed from {@link RssFeedIndexingExecutor} in the sibling {@code source.rss}
 * package (#1113); still not part of any cross-module API surface.
 */
public class DetailPageExtractor {

  private final TargetAddressValidator targetAddressValidator;
  private final IndexingProperties.Rss properties;

  public DetailPageExtractor(
      TargetAddressValidator targetAddressValidator, IndexingProperties.Rss properties) {
    this.targetAddressValidator = targetAddressValidator;
    this.properties = properties;
  }

  /** An entry's detail page, reduced to its main content's text and attachment candidates. */
  public record DetailPage(String mainText, List<AttachmentCandidate> attachments) {}

  /**
   * Fetches {@code entryUrl} and extracts its main content, following up to {@link
   * RedirectFollowingFetcher#MAX_REDIRECTS} redirects that stay within {@code entryUrl}'s own
   * origin ({@link RedirectFollowingFetcher.RedirectPolicy#REJECT_OFF_ORIGIN}) - an entry's {@code
   * <link>} is content the feed operator controls, not a target the library owner vouches for, so a
   * redirect leaving that origin is refused outright rather than followed anonymized.
   *
   * @throws RejectedByRemoteException if the remote end declined outright (403/429) or a redirect
   *     would leave {@code entryUrl}'s own origin or downgrade the protocol
   * @throws UnsupportedContentTypeException if the response's {@code Content-Type} is not HTML
   * @throws IOException if the page exceeds {@link IndexingProperties.Rss#maxPageSizeBytes()} or
   *     any other transport failure
   */
  public DetailPage fetch(HttpClient httpClient, String entryUrl, String authHeader)
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response = sendDetailPageRequest(httpClient, entryUrl, authHeader);

    // Every path below - the early rejections and the ordinary 200 - must close the response
    // body, hence try-with-resources around the whole evaluation. A foreign-host redirect is
    // already rejected inside sendDetailPageRequest (REJECT_OFF_ORIGIN), before a response for
    // that hop is ever returned here - no separate check is needed on the response this method
    // receives.
    try (InputStream body = response.body()) {
      if (response.statusCode() == 403 || response.statusCode() == 429) {
        throw new RejectedByRemoteException(
            "HTTP " + response.statusCode(),
            "Vom Quellserver abgewiesen (HTTP " + response.statusCode() + ")");
      }
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " for URL: " + entryUrl);
      }

      String contentType = response.headers().firstValue("Content-Type").orElse(null);
      if (!isHtmlContentType(contentType)) {
        // A <link> pointing straight at a PDF (or anything else that is not HTML) must never be
        // pushed through Jsoup - attachments are handled separately.
        throw new UnsupportedContentTypeException(
            contentType != null ? contentType : "(kein Content-Type)");
      }

      byte[] pageBytes;
      try {
        pageBytes = readBounded(body);
      } catch (PageTooLargeException e) {
        throw new IOException(
            "Detail page exceeds the configured limit of "
                + properties.maxPageSizeBytes()
                + " bytes: "
                + entryUrl);
      }

      // The server's declared charset wins when present; otherwise Jsoup.parse(InputStream, ...)
      // itself detects the charset from a BOM or a <meta> tag and falls back to UTF-8 - never a
      // hardcoded StandardCharsets.UTF_8, which silently mangles e.g. ISO-8859-1 into U+FFFD.
      Document htmlDoc =
          Jsoup.parse(new ByteArrayInputStream(pageBytes), charsetNameFrom(contentType), entryUrl);
      // nav/header/footer/menu-ish elements never survive into the index, regardless of whether
      // they sit inside or outside the matched main element below.
      htmlDoc
          .select(
              "nav, header, footer, [role=navigation], [role=banner], [role=contentinfo],"
                  + " .nav, .navigation, .menu, .breadcrumb, script, style, noscript")
          .remove();

      Element main = htmlDoc.selectFirst(properties.mainContentSelector());
      Element content = main != null ? main : htmlDoc.body();
      if (content == null) {
        return new DetailPage("", List.of());
      }
      List<AttachmentCandidate> attachments =
          properties.attachmentProfile().findAttachments(content, URI.create(entryUrl));
      return new DetailPage(content.text(), attachments);
    }
  }

  /**
   * Sends the detail-page request for {@code entryUrl}, following up to {@link
   * RedirectFollowingFetcher#MAX_REDIRECTS} same-origin redirects via the shared {@link
   * RedirectFollowingFetcher#sendFollowingRedirects} - {@code httpClient} (built with {@code
   * Redirect.NEVER}) never follows one on its own. A redirect off origin (different host/scheme, or
   * a protocol downgrade) is rejected under {@link
   * RedirectFollowingFetcher.RedirectPolicy#REJECT_OFF_ORIGIN}, before the foreign target is
   * contacted - remapped here to a {@link RejectedByRemoteException} with the identical wording, so
   * this class's own rejection handling stays uniform regardless of cause.
   *
   * <p>{@code authHeader} is sent on every hop this loop reaches - a foreign host is always
   * rejected before its request is built, so the header is never resent outside {@code entryUrl}'s
   * own origin.
   */
  private HttpResponse<InputStream> sendDetailPageRequest(
      HttpClient httpClient, String entryUrl, String authHeader)
      throws IOException, InterruptedException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", properties.userAgent());
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }
    try {
      return RedirectFollowingFetcher.sendFollowingRedirects(
          httpClient,
          entryUrl,
          Duration.ofSeconds(30),
          headers,
          targetAddressValidator,
          RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);
    } catch (RedirectFollowingFetcher.RedirectRejectedException e) {
      throw new RejectedByRemoteException(e.getMessage(), e.userMessage());
    }
  }

  /** Whether {@code contentType} (the raw {@code Content-Type} header value) denotes HTML. */
  private static boolean isHtmlContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml");
  }

  /**
   * Extracts the {@code charset} parameter from a {@code Content-Type} header value, or {@code
   * null} when absent - {@link Jsoup#parse(InputStream, String, String)} treats {@code null} as
   * "detect from the document itself".
   */
  private static String charsetNameFrom(String contentType) {
    if (contentType == null) {
      return null;
    }
    for (String part : contentType.split(";")) {
      String trimmed = part.strip();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
        String charset = trimmed.substring("charset=".length()).strip();
        // Some servers quote the value ("charset=\"iso-8859-1\"") - Jsoup expects a bare name.
        if (charset.length() >= 2 && charset.startsWith("\"") && charset.endsWith("\"")) {
          charset = charset.substring(1, charset.length() - 1);
        }
        return charset.isBlank() ? null : charset;
      }
    }
    return null;
  }

  /**
   * Reads at most {@link IndexingProperties.Rss#maxPageSizeBytes()} from {@code in}, throwing
   * {@link PageTooLargeException} the moment a further byte would exceed the limit - enforced while
   * streaming, not after the full response has already been downloaded.
   */
  private byte[] readBounded(InputStream in) throws IOException {
    byte[] probe =
        in.readNBytes(
            Math.toIntExact(Math.min(properties.maxPageSizeBytes() + 1, Integer.MAX_VALUE)));
    if (probe.length > properties.maxPageSizeBytes()) {
      throw new PageTooLargeException();
    }
    return probe;
  }

  /** Thrown by {@link #readBounded} when the configured byte limit is exceeded while streaming. */
  private static final class PageTooLargeException extends RuntimeException {}

  /**
   * Thrown when the remote end itself declined to hand over a detail page (403/429, a redirect to a
   * foreign host, or a refused protocol downgrade) - kept distinct from an ordinary {@link
   * IOException} so the caller can log and count it separately from a processing failure. {@link
   * #userMessage()} is a German, cause-specific, sanitized run-log text, distinct from this
   * exception's own message, which stays the unsanitized, developer-facing detail for the log only.
   */
  public static final class RejectedByRemoteException extends RuntimeException {
    private final String userMessage;

    RejectedByRemoteException(String logMessage, String userMessage) {
      super(logMessage);
      this.userMessage = userMessage;
    }

    public String userMessage() {
      return userMessage;
    }
  }

  /**
   * Thrown when a detail page's {@code Content-Type} is not HTML - e.g. a {@code <link>} pointing
   * straight at a PDF. Kept distinct from {@link RejectedByRemoteException}: the remote end
   * answered normally here, it just did not hand over something this class can extract text from.
   */
  public static final class UnsupportedContentTypeException extends RuntimeException {
    UnsupportedContentTypeException(String actualContentType) {
      super(actualContentType);
    }
  }
}
