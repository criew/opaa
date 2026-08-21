package io.opaa.indexing;

import java.time.Duration;
import java.util.List;
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
 *     batch size that avoids memory spikes during embedding generation. Valid range: 1–1 000. Since
 *     #734, this is also the unit {@link #embeddingConcurrency} slices a document's chunks into
 *     before dispatching them concurrently (see that parameter's Javadoc) - previously dead
 *     configuration (nothing in {@code io.opaa.indexing} read it), now load-bearing.
 * @param retryAttempts number of retry attempts for transient failures. Default 3: standard retry
 *     count used with exponential backoff. Valid range: 0–10.
 * @param threadPool thread pool settings for async indexing. Defaults (core=2, max=4, queue=20) are
 *     conservative values suitable for typical single-server deployments.
 * @param rss settings governing {@link IndexingSourceType#RSS_FEED} runs (#467) - obergrenzen and
 *     politeness settings the executor must apply against feed operators it does not control (see
 *     {@link Rss}'s own Javadoc).
 * @param filesystemAllowlist absolute base directories a {@code FILESYSTEM} library's {@code
 *     sourcePath} must resolve underneath (#484, ADR-0018 Entscheidung 6). Every path with
 *     anlage-recht may still choose {@code FILESYSTEM} as a quellentyp - this is the actual
 *     security boundary: a caller-chosen path outside every configured base directory is rejected,
 *     and an <b>empty allowlist (the default) disables the FILESYSTEM quellentyp entirely</b>
 *     rather than defaulting to "everything allowed". Checked by {@link FilesystemPathAllowlist},
 *     both at library creation/update time ({@code KnowledgeLibraryService}) and again at run time
 *     ({@link AsyncIndexingExecutor}), because the allowlist can be narrowed after a library was
 *     created. Bound from a comma-separated environment variable ({@code
 *     OPAA_INDEXING_FILESYSTEM_ALLOWLIST}) like any other {@code List<String>} property.
 * @param staleJobTimeout how long a run may stay {@link JobStatus#RUNNING} before {@code
 *     IndexingJobRecoveryScheduler} treats it as orphaned and fails it, even without an application
 *     restart (#501) - see {@code IndexingJobService#recoverStaleJobs}. Default 4 hours: generous
 *     enough for a large FILESYSTEM/HTTP_DIRECTORY/RSS_FEED run to finish normally, short enough
 *     that a genuinely stuck run does not lock its library out for days.
 * @param targetValidation the SSRF target-address check {@link TargetAddressValidator} applies to
 *     every {@code HTTP_DIRECTORY}/{@code RSS_FEED} fetch (#267) - see {@link TargetValidation}'s
 *     own Javadoc.
 * @param embeddingConcurrency the maximum number of {@code batchSize}-sized chunk batches a single
 *     document's {@link io.opaa.indexing.FileProcessingService#storeChunks} may embed and persist
 *     concurrently (#734, {@code OPAA_INDEXING_EMBEDDING_CONCURRENCY}). Bound to a shared,
 *     fixed-size pool ({@code IndexingConfiguration#embeddingTaskExecutor}) - not per document, so
 *     the total number of concurrent embedding calls across every indexing run in the process never
 *     exceeds this value, regardless of how many libraries index at once. Default 3: moderate
 *     concurrency chosen from #734's own benchmark - a CPU-bound local Ollama shows no throughput
 *     gain past concurrency 1 (its embedding computation itself serializes), while a
 *     network-latency-bound API/GPU backend scales close to linearly with concurrency in the same
 *     benchmark; 3 stays conservative for the former without giving up all of the latter's
 *     headroom. An operator fronting a GPU-backed or hosted embedding API can raise this (8-16 is a
 *     reasonable starting point per that same benchmark - see docs/deployment.md) once they know
 *     their backend actually serves concurrent requests in parallel. <b>A value of 1 reproduces the
 *     exact pre-#734 sequential behaviour</b> - {@link
 *     io.opaa.indexing.FileProcessingService#storeChunks} takes an entirely different, untouched
 *     code path in that case, not merely a pool of size one. Valid range: 1–32 - the upper bound
 *     keeps this a moderate, bounded fan-out rather than the unbounded one #734 explicitly warns
 *     against.
 */
@ConfigurationProperties(prefix = "opaa.indexing")
public record IndexingProperties(
    String documentPath,
    int chunkSize,
    int chunkOverlap,
    int batchSize,
    int retryAttempts,
    ThreadPool threadPool,
    Rss rss,
    List<String> filesystemAllowlist,
    Duration staleJobTimeout,
    TargetValidation targetValidation,
    int embeddingConcurrency) {

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

  /**
   * SSRF hardening for {@code HTTP_DIRECTORY}/{@code RSS_FEED} fetches (#267): {@link
   * TargetAddressValidator} rejects a target whose resolved address lies in a loopback, link-local,
   * private or otherwise non-routable range, and any non-{@code http(s)} scheme.
   *
   * @param enabled whether the check runs at all. Default {@code true} - an operator with a
   *     legitimate internal document source turns this off deliberately (env var {@code
   *     OPAA_INDEXING_TARGET_VALIDATION_ENABLED}), the check does not default to permissive.
   * @param allowlist hostnames (exact, case-insensitive match against the URI's own host - not a
   *     resolved address) exempted from the address check even while {@code enabled} is {@code
   *     true} - lets an operator name specific internal sources without disabling the check for
   *     every other target. Comma-separated environment variable ({@code
   *     OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST}), mirroring {@link
   *     IndexingProperties#filesystemAllowlist()}'s configuration style. Empty by default.
   */
  public record TargetValidation(boolean enabled, List<String> allowlist) {

    public TargetValidation {
      if (allowlist == null) {
        allowlist = List.of();
      }
    }
  }
}
