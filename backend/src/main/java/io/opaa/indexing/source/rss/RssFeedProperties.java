package io.opaa.indexing.source.rss;

import io.opaa.indexing.attachment.AttachmentProfile;
import io.opaa.indexing.format.file.html.HtmlContentRoots;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Politeness and DoS-hardening settings for RSS feed runs ({@code opaa.indexing.rss}). The
 * addresses an RSS run touches - the feed itself and every entry's detail page - come from the feed
 * operator, not from OPAA's own configuration; {@code RssFeedParser} deliberately does not enforce
 * any of these limits itself (it is a pure, unbounded parser meant to run without network or
 * database), so the executor that drives it is the only place left to apply them. The {@code
 * User-Agent} and the {@code 429} tolerance are {@code SourceHttpProperties}', shared with every
 * other connector.
 *
 * @param maxEntries the maximum number of feed entries processed in a single run. Excess entries
 *     are logged and dropped, not treated as an error.
 * @param maxFeedSizeBytes the maximum number of bytes read from the feed itself before parsing
 *     aborts. Enforced while streaming the response, not after it has already been fully downloaded
 *     - the parser has no cap of its own.
 * @param maxPageSizeBytes the maximum number of bytes read from a single entry's detail page. A
 *     page exceeding this is skipped like any other rejection by the remote end, not treated as a
 *     run-ending failure.
 * @param requestDelayMs the minimum delay, in milliseconds, between two detail-page requests -
 *     being a well-behaved crawler against sites OPAA does not operate. Default 1000: a
 *     conservative one request per second.
 * @param mainContentSelector the CSS selector (Jsoup syntax) used to find a detail page's main
 *     content, tried against the whole document ({@code HtmlContentRoots}). Falls back to {@code
 *     body} when it matches nothing, so an unusual page still yields the full page rather than
 *     nothing at all.
 * @param attachmentProfile the {@link AttachmentProfile} deciding which links on a detail page
 *     count as attachments. Defaults to {@link AttachmentProfile#GENERIC}. This is deliberately an
 *     application property, not a per-request field on {@code IndexingTriggerRequest} - ADR-0018
 *     already moves persistent source configuration from the trigger request onto the knowledge
 *     library.
 * @param maxAttachmentsPerEntry the maximum number of attachments downloaded per RSS entry. Excess
 *     candidates are logged and dropped, not treated as an error - mirrors {@link #maxEntries}'s
 *     truncation-not-failure treatment.
 * @param maxAttachmentSizeBytes the maximum number of bytes read from a single attachment. Enforced
 *     while streaming the response, not after it has already been fully downloaded (mirrors {@link
 *     #maxPageSizeBytes}).
 */
@ConfigurationProperties(prefix = "opaa.indexing.rss")
public record RssFeedProperties(
    int maxEntries,
    long maxFeedSizeBytes,
    long maxPageSizeBytes,
    long requestDelayMs,
    String mainContentSelector,
    AttachmentProfile attachmentProfile,
    int maxAttachmentsPerEntry,
    long maxAttachmentSizeBytes) {

  /** The HTML pipeline's own choice, so a file and a feed page are reduced the same way. */
  static final String DEFAULT_MAIN_CONTENT_SELECTOR =
      HtmlContentRoots.DEFAULT_MAIN_CONTENT_SELECTOR;

  public RssFeedProperties {
    if (maxEntries <= 0) {
      maxEntries = 200;
    }
    if (maxFeedSizeBytes <= 0) {
      maxFeedSizeBytes = 10_485_760L; // 10 MiB
    }
    if (maxPageSizeBytes <= 0) {
      maxPageSizeBytes = 5_242_880L; // 5 MiB
    }
    if (requestDelayMs < 0) {
      requestDelayMs = 1000L;
    }
    if (mainContentSelector == null || mainContentSelector.isBlank()) {
      mainContentSelector = DEFAULT_MAIN_CONTENT_SELECTOR;
    }
    if (attachmentProfile == null) {
      attachmentProfile = AttachmentProfile.GENERIC;
    }
    if (maxAttachmentsPerEntry <= 0) {
      maxAttachmentsPerEntry = 10;
    }
    if (maxAttachmentSizeBytes <= 0) {
      maxAttachmentSizeBytes = 20_971_520L; // 20 MiB
    }
  }
}
