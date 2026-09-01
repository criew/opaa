package io.opaa.indexing;

import io.opaa.indexing.source.attachment.AttachmentProfile;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the document indexing pipeline.
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
 * @param filesystemAllowlist absolute base directories a {@code FILESYSTEM} library's {@code
 *     sourcePath} must resolve underneath (ADR-0018 Entscheidung 6) - the actual security boundary:
 *     a caller-chosen path outside every configured base directory is rejected, and an <b>empty
 *     allowlist (the default) disables the FILESYSTEM quellentyp entirely</b> rather than
 *     defaulting to "everything allowed". Checked by {@code FilesystemPathAllowlist}, both at
 *     library creation/update time ({@code KnowledgeLibraryService}) and again at run time ({@link
 *     AsyncIndexingExecutor}), because the allowlist can be narrowed after a library was created.
 * @param staleJobTimeout how long a run may stay {@link JobStatus#RUNNING} before {@code
 *     IndexingJobRecoveryScheduler} treats it as orphaned and fails it, even without an application
 *     restart - see {@code IndexingJobService#recoverStaleJobs}. Default 4 hours: generous enough
 *     for a large run to finish normally, short enough that a genuinely stuck run does not lock its
 *     library out for days.
 * @param targetValidation the SSRF target-address check {@code
 *     io.opaa.sourceaccess.TargetAddressValidator} applies to every {@code HTTP_DIRECTORY}/{@code
 *     RSS_FEED} fetch - see {@link TargetValidation}'s own Javadoc.
 * @param fullTextBackfill batch size for the resumable full-text backfill of the pre-#1047 chunk
 *     bestand (docs/features/hybrid-retrieval.md, "Arbeitspaket 2a"), driven by {@link
 *     FullTextBackfillScheduler}. Ebene 1 (docs/features/hybrid-retrieval.md,
 *     "Konfigurations-Ebenenmodell") - an internal default overridable via properties, not an
 *     administration-UI setting.
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
    List<String> filesystemAllowlist,
    Duration staleJobTimeout,
    TargetValidation targetValidation,
    FullTextBackfill fullTextBackfill,
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
      rss = new Rss(200, 10_485_760L, 5_242_880L, 1000L, null, null, null, 0, 0L);
    }
    if (filesystemAllowlist == null) {
      filesystemAllowlist = List.of();
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
    if (fullTextBackfill == null) {
      fullTextBackfill = new FullTextBackfill(200);
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
   * or database), so the executor that drives it is the only place left to apply them.
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
   * @param userAgent the {@code User-Agent} header sent with every request this executor makes.
   *     Deliberately truthful by default - impersonating a browser is explicitly out of scope.
   * @param mainContentSelector the CSS selector (Jsoup syntax) used to find a detail page's main
   *     content, tried against the whole document. Falls back to {@code body} when it matches
   *     nothing, so an unusual page still yields the full page's text rather than nothing at all.
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
      String userAgent,
      String mainContentSelector,
      AttachmentProfile attachmentProfile,
      int maxAttachmentsPerEntry,
      long maxAttachmentSizeBytes) {

    /** Truthful default {@code User-Agent} - never a value that impersonates a browser. */
    static final String DEFAULT_USER_AGENT = "OPAA-Indexer/1.0";

    /**
     * Tried in order against the whole document; the first selector that matches anything wins.
     * {@code main}/{@code article}/{@code [role=main]} cover the vast majority of German public
     * administration CMS templates without any per-site configuration.
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

  /**
   * @param batchSize the upper bound on chunks one {@link FullTextBackfillService#backfillBatch}
   *     call indexes. Default 200: small enough that a single batch's {@code SELECT ... NOT EXISTS}
   *     scan and subsequent inserts stay cheap on every {@link FullTextBackfillScheduler} tick,
   *     large enough that a realistically sized backlog drains in a reasonable number of ticks.
   *     Valid range: 1-10 000.
   */
  public record FullTextBackfill(int batchSize) {

    public FullTextBackfill {
      if (batchSize <= 0) {
        batchSize = 200;
      }
      if (batchSize > 10_000) {
        throw new IllegalArgumentException(
            "fullTextBackfill.batchSize must be at most 10000, got " + batchSize);
      }
    }
  }
}
