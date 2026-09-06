package io.opaa.indexing;

import io.opaa.indexing.pipeline.html.HtmlContentRoots;
import io.opaa.indexing.source.attachment.AttachmentProfile;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the document indexing pipeline.
 *
 * <p>A concern specific to a single pipeline, connector or crawl path lives in a property block of
 * its own ({@code CrawlProperties}, {@code TabularProperties}, {@code OdfProperties}, {@code
 * ConfluenceProperties}, ...) rather than as a component here: this record is bound positionally by
 * many call sites, and every added component touches all of them.
 *
 * @param chunkSize target token count per chunk. Default 1000: balances sufficient context per
 *     chunk against retrieval granularity. Valid range: 1–10 000.
 * @param chunkOverlap number of tokens each chunk repeats from the end of its predecessor, so that
 *     a statement straddling a chunk boundary survives in at least one chunk as a whole. Must be
 *     smaller than {@code chunkSize}; 0 disables overlap. A negative value is normalised to 0.
 * @param batchSize the upper bound on chunks sent to the embedding model in one call. Default 50:
 *     moderate batch size that avoids memory spikes during embedding generation. Valid range:
 *     1–1000. Also the upper bound {@link #embeddingConcurrency}'s own sub-batch sizing respects
 *     (see that parameter's Javadoc for the actual sizing formula, which is not simply this value).
 * @param threadPool thread pool settings for async indexing. Defaults (core=2, max=4, queue=20) are
 *     conservative values suitable for typical single-server deployments.
 * @param rss settings governing {@code IndexingSourceType#RSS_FEED} runs - obergrenzen and
 *     politeness settings the executor must apply against feed operators it does not control (see
 *     {@link Rss}'s own Javadoc).
 * @param staleJobTimeout how long a run may stay {@link JobStatus#RUNNING} before {@code
 *     IndexingJobRecoveryScheduler} treats it as orphaned and fails it, even without an application
 *     restart - see {@code IndexingJobService#recoverStaleJobs}. Default 4 hours: generous enough
 *     for a large run to finish normally, short enough that a genuinely stuck run does not lock its
 *     library out for days.
 * @param targetValidation the SSRF target-address check {@code
 *     io.opaa.sourceaccess.TargetAddressValidator} applies to every {@code HTTP_DIRECTORY}/{@code
 *     RSS_FEED} fetch - see {@link TargetValidation}'s own Javadoc.
 * @param embeddingConcurrency the maximum number of sub-batches a single document's chunks are
 *     split into for concurrent embedding and persistence ({@code
 *     OPAA_INDEXING_EMBEDDING_CONCURRENCY}) - see {@code
 *     io.opaa.indexing.FileProcessingService#subBatchSize} for the exact sizing formula. Concurrent
 *     sub-batches of a splitting document share a single, fixed-size pool ({@code
 *     IndexingConfiguration#embeddingTaskExecutor}) process-wide, not one per document or per
 *     library - but that pool bounds only the fan-out of documents that are actually being split,
 *     not the total number of concurrent embedding calls the process makes: a document that is not
 *     split embeds directly on whichever thread called {@code storeChunks}, entirely outside this
 *     pool. Default 3: a CPU-bound local Ollama shows next to no throughput gain past concurrency 1
 *     (its embedding computation serializes), while a network-latency-bound API/GPU backend scales
 *     close to linearly with concurrency; 3 stays conservative for the former without giving up all
 *     of the latter's headroom. An operator fronting a GPU-backed or hosted embedding API can raise
 *     this (8-16 is a reasonable starting point - see docs/handbuch/deployment.md). A value of 1
 *     reproduces the exact sequential behaviour - {@code
 *     io.opaa.indexing.FileProcessingService#storeChunks} takes an entirely different code path in
 *     that case, not merely a pool of size one. Valid range: 1–32.
 */
@ConfigurationProperties(prefix = "opaa.indexing")
public record IndexingProperties(
    int chunkSize,
    int chunkOverlap,
    int batchSize,
    ThreadPool threadPool,
    Rss rss,
    Duration staleJobTimeout,
    TargetValidation targetValidation,
    int embeddingConcurrency) {

  public IndexingProperties {
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
    if (threadPool == null) {
      threadPool = new ThreadPool(2, 4, 20);
    }
    if (rss == null) {
      rss = new Rss(200, 10_485_760L, 5_242_880L, 1000L, null, null, 0, 0L);
    }
    if (staleJobTimeout == null) {
      staleJobTimeout = Duration.ofHours(4);
    }
    if (staleJobTimeout.isNegative() || staleJobTimeout.isZero()) {
      throw new IllegalArgumentException(
          "staleJobTimeout must be positive, got " + staleJobTimeout);
    }
    if (targetValidation == null) {
      targetValidation = new TargetValidation(true, List.of());
    }
    if (embeddingConcurrency <= 0) {
      embeddingConcurrency = 3;
    }
    if (embeddingConcurrency > 32) {
      throw new IllegalArgumentException(
          "embeddingConcurrency must be at most 32, got " + embeddingConcurrency);
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
   * Politeness and DoS-hardening settings for {@code IndexingSourceType#RSS_FEED} runs. The
   * addresses an RSS run touches - the feed itself and every entry's detail page - come from the
   * feed operator, not from OPAA's own configuration; {@code RssFeedParser} deliberately does not
   * enforce any of these limits itself (it is a pure, unbounded parser meant to run without network
   * or database), so the executor that drives it is the only place left to apply them. The {@code
   * User-Agent} and the {@code 429} tolerance are {@link SourceHttpProperties}', shared with every
   * other connector.
   *
   * @param maxEntries the maximum number of feed entries processed in a single run. Excess entries
   *     are logged and dropped, not treated as an error.
   * @param maxFeedSizeBytes the maximum number of bytes read from the feed itself before parsing
   *     aborts. Enforced while streaming the response, not after it has already been fully
   *     downloaded - the parser has no cap of its own.
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
   *     count as attachments. Defaults to {@link AttachmentProfile#GENERIC}. This is deliberately
   *     an application property, not a per-request field on {@code IndexingTriggerRequest} -
   *     ADR-0018 already moves persistent source configuration from the trigger request onto the
   *     knowledge library.
   * @param maxAttachmentsPerEntry the maximum number of attachments downloaded per RSS entry.
   *     Excess candidates are logged and dropped, not treated as an error - mirrors {@link
   *     #maxEntries}'s truncation-not-failure treatment.
   * @param maxAttachmentSizeBytes the maximum number of bytes read from a single attachment.
   *     Enforced while streaming the response, not after it has already been fully downloaded
   *     (mirrors {@link #maxPageSizeBytes}).
   */
  public record Rss(
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

  /**
   * SSRF hardening for {@code HTTP_DIRECTORY}/{@code RSS_FEED} fetches: {@code
   * io.opaa.sourceaccess.TargetAddressValidator} rejects a target whose resolved address lies in a
   * loopback, link-local, private or otherwise non-routable range, and any non-{@code http(s)}
   * scheme.
   *
   * @param enabled whether the check runs at all. Default {@code true} - an operator with a
   *     legitimate internal document source turns this off deliberately; the check does not default
   *     to permissive.
   * @param allowlist hostnames (exact, case-insensitive match against the URI's own host - not a
   *     resolved address) exempted from the address check even while {@code enabled} is {@code
   *     true} - lets an operator name specific internal sources without disabling the check for
   *     every other target. Empty by default.
   */
  public record TargetValidation(boolean enabled, List<String> allowlist) {

    public TargetValidation {
      if (allowlist == null) {
        allowlist = List.of();
      }
    }
  }
}
