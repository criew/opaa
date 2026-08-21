package io.opaa.library;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the REST document upload endpoint (#420,
 * docs/features/knowledge-sources.md#upload).
 *
 * @param storagePath filesystem path where uploaded files are stored, one subdirectory per library
 *     (by library id) so files from different libraries never collide and a library's uploads can
 *     be told apart on disk. Deliberately separate from {@code opaa.indexing.document-path}: that
 *     directory is crawled by the filesystem indexing path (#207) and is operator-managed, whereas
 *     this one is written to exclusively by {@link LibraryDocumentService}.
 * @param maxFileSize maximum accepted upload size in bytes. Default 50 MiB (52 428 800): generous
 *     enough for a typical scanned Dienstanweisung while still bounding memory and disk use per
 *     upload; see #420's acceptance criteria for the resulting 413 response.
 * @param threadPool thread pool settings for {@code IndexingConfiguration#uploadTaskExecutor}
 *     (#434). Deliberately its own property block, not a reuse of {@code opaa.indexing.thread-pool}
 *     (#614, PR #589 second review round): {@code uploadTaskExecutor} and {@code
 *     indexingTaskExecutor} are two independent {@code ThreadPoolTaskExecutor} beans - an operator
 *     raising {@code opaa.indexing.thread-pool.max-size} to cap total indexing concurrency would,
 *     while both executors read the same properties, actually double it instead, since the upload
 *     pool grew by the same amount unnoticed. Defaults (core=2, max=4, queue=20) mirror {@code
 *     opaa.indexing.thread-pool}'s own conservative defaults.
 * @param pendingRecoveryThresholdMinutes how long a document may stay {@code PENDING} before {@code
 *     UploadPendingRecoveryRunner} treats it as abandoned by a process that died mid-task and sets
 *     it to {@code FAILED} at the next application startup (#614). Default 30 minutes: comfortably
 *     longer than parsing/embedding even a large upload should ever take, so a row is only ever
 *     caught here because nothing is actually still working on it.
 * @param libraryQuotaBytes the maximum total size, summed across every document a library holds,
 *     that library may occupy (#119, Maintainer-Entscheidung: Standardkontingent je Bibliothek).
 *     {@code application.yml}'s own default resolves the underlying env var to 10 GiB (10 737 418
 *     240) when unset - generous for a working knowledge library while still bounding how much
 *     disk/vector-store space a single library - upload or connector-fed alike - can consume
 *     unchecked. <b>Deliberately not defaulted here the way the other properties in this record are
 *     (PR #700 review, finding 2):</b> {@code 0} or negative means <em>unbegrenzt</em> (no quota
 *     enforced at all), not "fall back to 10 GiB" - an operator with an existing library already
 *     larger than 10 GiB must be able to opt out of the new limit entirely rather than have every
 *     upload and connector document into it rejected the moment this version starts. Enforced by
 *     {@link LibraryStorageQuotaService} at every ingestion path that stores document content
 *     (upload via {@link LibraryDocumentService}, and the FILESYSTEM/HTTP_DIRECTORY/ RSS_FEED
 *     connector paths via {@code io.opaa.indexing.FileProcessingService}), not merely the upload
 *     endpoint - a connector run can grow a library's bestand just as much as a human upload can.
 */
@ConfigurationProperties(prefix = "opaa.upload")
public record UploadProperties(
    String storagePath,
    long maxFileSize,
    ThreadPool threadPool,
    int pendingRecoveryThresholdMinutes,
    long libraryQuotaBytes) {

  public UploadProperties {
    if (storagePath == null || storagePath.isBlank()) {
      storagePath = "./uploads";
    }
    if (maxFileSize <= 0) {
      maxFileSize = 50L * 1024 * 1024;
    }
    if (threadPool == null) {
      threadPool = new ThreadPool(2, 4, 20);
    }
    if (pendingRecoveryThresholdMinutes <= 0) {
      pendingRecoveryThresholdMinutes = 30;
    }
    // libraryQuotaBytes is deliberately NOT defaulted here - see its own Javadoc above. A value
    // <= 0 is a real, supported "unbegrenzt" configuration, resolved by
    // LibraryStorageQuotaService, not normalized away on this record.
  }

  /** Mirrors {@code IndexingProperties.ThreadPool}'s own validation - see its Javadoc. */
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
}
