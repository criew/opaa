package io.opaa.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the document indexing pipeline.
 *
 * @param documentPath filesystem path where source documents are stored. Unused by production code
 *     since ADR-0018/#478: a FILESYSTEM library now reads its own {@code sourcePath} instead of
 *     this single, application-wide value. Kept as a configuration property rather than removed
 *     outright, since deleting it would ripple through the many tests that still set {@code
 *     opaa.indexing.document-path} for unrelated reasons; a follow-up cleanup can remove it once
 *     those are untangled.
 * @param chunkSize target token count per chunk. Default 1000: standard for token-based chunking —
 *     balances sufficient context per chunk against retrieval granularity. Valid range: 1–10 000.
 * @param chunkOverlap number of tokens each chunk repeats from the end of its predecessor, so that
 *     a statement straddling a chunk boundary survives in at least one chunk as a whole (issue
 *     #374). Must be smaller than {@code chunkSize}; 0 disables overlap. A negative value is
 *     normalised to 0.
 * @param batchSize number of chunks sent to the embedding model in one call. Default 50: moderate
 *     batch size that avoids memory spikes during embedding generation. Valid range: 1–1 000.
 * @param retryAttempts number of retry attempts for transient failures. Default 3: standard retry
 *     count used with exponential backoff. Valid range: 0–10.
 * @param threadPool thread pool settings for async indexing. Defaults (core=2, max=4, queue=20) are
 *     conservative values suitable for typical single-server deployments.
 * @param rss settings governing {@link IndexingSourceType#RSS_FEED} runs (#467) - obergrenzen and
 *     politeness settings the executor must apply against feed operators it does not control (see
 *     {@link Rss}'s own Javadoc).
 */
@ConfigurationProperties(prefix = "opaa.indexing")
public record IndexingProperties(
    String documentPath,
    int chunkSize,
    int chunkOverlap,
    int batchSize,
    int retryAttempts,
    ThreadPool threadPool,
    Rss rss) {

  public IndexingProperties {
    if (documentPath == null) {
      documentPath = "./documents";
    }
    if (chunkSize <= 0) {
      chunkSize = 1000;
    }
    if (chunkSize > 10000) {
      throw new IllegalArgumentException("chunkSize must be at most 10000, got " + chunkSize);
    }
    // 0 is a meaningful value here (no overlap), so it is not replaced by a default the way
    // chunkSize/batchSize are. Only a nonsensical negative value is normalised.
    if (chunkOverlap < 0) {
      chunkOverlap = 0;
    }
    if (chunkOverlap >= chunkSize) {
      throw new IllegalArgumentException(
          "chunkOverlap must be smaller than chunkSize ("
              + chunkSize
              + "), got "
              + chunkOverlap
              + "; an overlap of at least the chunk size would never advance through the text");
    }
    if (batchSize <= 0) {
      batchSize = 50;
    }
    if (batchSize > 1000) {
      throw new IllegalArgumentException("batchSize must be at most 1000, got " + batchSize);
    }
    if (retryAttempts < 0) {
      retryAttempts = 3;
    }
    if (retryAttempts > 10) {
      throw new IllegalArgumentException("retryAttempts must be at most 10, got " + retryAttempts);
    }
    if (threadPool == null) {
      threadPool = new ThreadPool(2, 4, 20);
    }
    if (rss == null) {
      rss = new Rss(200, 10_485_760L, 5_242_880L, 1000L, null, null, null, 0, 0L);
    }
  }

  public record ThreadPool(int coreSize, int maxSize, int queueCapacity) {

    public ThreadPool {
      if (coreSize <= 0) {
        coreSize = 2;
      }
      if (maxSize <= 0) {
        maxSize = 4;
      }
      if (queueCapacity < 0) {
        queueCapacity = 20;
      }
    }
  }

  /**
   * Politeness and DoS-hardening settings for {@link IndexingSourceType#RSS_FEED} runs (#467, PR
   * #474 review). The addresses an RSS run touches - the feed itself and every entry's detail page
   * - come from the feed operator, not from OPAA's own configuration; {@link RssFeedParser}
   * deliberately does not enforce any of these limits itself (it is a pure, unbounded parser meant
   * to run without network or database), so the executor that drives it is the only place left to
   * apply them.
   *
   * @param maxEntries the maximum number of feed entries processed in a single run. Excess entries
   *     are logged and dropped, not treated as an error - a feed is allowed to simply carry more
   *     entries than one run processes.
   * @param maxFeedSizeBytes the maximum number of bytes read from the feed itself before parsing
   *     aborts. Enforced while streaming the response, not after it has already been fully
   *     downloaded (PR #474 review) - the parser has no cap of its own.
   * @param maxPageSizeBytes the maximum number of bytes read from a single entry's detail page. A
   *     page exceeding this is skipped like any other rejection by the remote end, not treated as a
   *     run-ending failure.
   * @param requestDelayMs the minimum delay, in milliseconds, between two detail-page requests -
   *     the "Kennung des abrufenden Programms" side of being a well-behaved crawler against sites
   *     OPAA does not operate. Default 1000: a conservative one request per second.
   * @param userAgent the {@code User-Agent} header sent with every request this executor makes.
   *     Deliberately truthful by default (see below) - impersonating a browser is explicitly out of
   *     scope (#467).
   * @param mainContentSelector the CSS selector (Jsoup syntax) used to find a detail page's main
   *     content, tried against the whole document. Falls back to {@code body} when it matches
   *     nothing, so an unusual page still yields the full page's text rather than nothing at all.
   * @param attachmentProfile the {@link AttachmentProfile} deciding which links on a detail page
   *     count as attachments (#468). Defaults to {@link AttachmentProfile#GENERIC}. This is
   *     deliberately an application property, not a per-request field on {@code
   *     IndexingTriggerRequest} - ADR-0018 (#486) is already moving persistent source configuration
   *     from the trigger request onto the knowledge library, and a new request field here would be
   *     thrown away the moment that lands. See the #468 pull request description for this deviation
   *     from the issue's "Profilwahl je Lauf" wording.
   * @param maxAttachmentsPerEntry the maximum number of attachments downloaded per RSS entry.
   *     Excess candidates are logged and dropped, not treated as an error - mirrors {@link
   *     #maxEntries}'s truncation-not-failure treatment.
   * @param maxAttachmentSizeBytes the maximum number of bytes read from a single attachment.
   *     Enforced while streaming the response, not after it has already been fully downloaded
   *     (mirrors {@link #maxPageSizeBytes}). An attachment exceeding this is skipped like any other
   *     rejected attachment, never a run-ending failure.
   */
  public record Rss(
      int maxEntries,
      long maxFeedSizeBytes,
      long maxPageSizeBytes,
      long requestDelayMs,
      String userAgent,
      String mainContentSelector,
      AttachmentProfile attachmentProfile,
      int maxAttachmentsPerEntry,
      long maxAttachmentSizeBytes) {

    /** Truthful default {@code User-Agent} - never a value that impersonates a browser (#467). */
    static final String DEFAULT_USER_AGENT = "OPAA-Indexer/1.0";

    /**
     * Tried in order against the whole document; the first selector that matches anything wins.
     * {@code main}/{@code article}/{@code [role=main]} cover the vast majority of German public
     * administration CMS templates (#467) without any per-site configuration.
     */
    static final String DEFAULT_MAIN_CONTENT_SELECTOR = "main, article, [role=main]";

    public Rss {
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
      if (userAgent == null || userAgent.isBlank()) {
        userAgent = DEFAULT_USER_AGENT;
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
}
