package io.opaa.indexing.source.web;

import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.source.attachment.AttachmentCandidate;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Fetches a single RSS entry's detail page and reduces it to its main content's text plus
 * attachment candidates, split out of {@code RssFeedIndexingExecutor}. Package-private - an
 * implementation detail of the executor, not a new public API.
 *
 * <p>{@code nav}/{@code header}/{@code footer}/menu-ish elements are stripped before the configured
 * {@link IndexingProperties.Rss#mainContentSelector()} is applied, so boilerplate inside the
 * matched main element does not survive either and is never considered for attachments.
 */
public class DetailPageExtractor {

  private final TargetAddressValidator targetAddressValidator;
  private final IndexingProperties.Rss properties;
  private final SourceRequestPolicy requestPolicy;

  public DetailPageExtractor(
      TargetAddressValidator targetAddressValidator,
      IndexingProperties.Rss properties,
      SourceRequestPolicy requestPolicy) {
    this.targetAddressValidator = targetAddressValidator;
    this.properties = properties;
    this.requestPolicy = requestPolicy;
  }

  /** An entry's detail page, reduced to its main content's text and attachment candidates. */
  public record DetailPage(String mainText, List<AttachmentCandidate> attachments) {}

  /**
   * Fetches {@code entryUrl} and extracts its main content, following redirects only within {@code
   * entryUrl}'s own origin ({@link RedirectFollowingFetcher.RedirectPolicy#REJECT_OFF_ORIGIN}) - an
   * entry's {@code <link>} is content the feed operator controls, so a redirect leaving that origin
   * is refused outright rather than followed anonymized.
   *
   * @throws RejectedByRemoteException if the remote end declined outright (403, or 429 past the
   *     {@link SourceRequestPolicy}'s retries) or a redirect would leave {@code entryUrl}'s own
   *     origin or downgrade the protocol
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
        // bounded while streaming, never after the whole response has been downloaded
        pageBytes = BoundedStreams.readFully(body, properties.maxPageSizeBytes());
      } catch (BoundedStreams.LimitExceededException e) {
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
   * own origin. A {@code 429} is waited out under the shared {@link SourceRequestPolicy}.
   */
  private HttpResponse<InputStream> sendDetailPageRequest(
      HttpClient httpClient, String entryUrl, String authHeader)
      throws IOException, InterruptedException {
    try {
      return RedirectFollowingFetcher.sendFollowingRedirects(
          httpClient,
          entryUrl,
          Duration.ofSeconds(30),
          requestPolicy.headers(authHeader),
          targetAddressValidator,
          RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN,
          requestPolicy.rateLimitHandling());
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
   * Thrown when the remote end itself declined to hand over a detail page (403, a 429 past every
   * retry, a redirect to a foreign host, or a refused protocol downgrade) - kept distinct from an ordinary {@link
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
