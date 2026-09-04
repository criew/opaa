package io.opaa.library;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the REST document upload endpoint (#420,
 * docs/features/knowledge-sources.md#upload).
 *
 * @param storagePath filesystem path where uploaded files are stored, one subdirectory per library
 *     (by library id) so files from different libraries never collide and a library's uploads can
 *     be told apart on disk. Deliberately separate from a FILESYSTEM library's own {@code
 *     sourcePath} (#207, ADR-0018): that directory is crawled by the filesystem indexing path and
 *     is operator-managed, whereas this one is written to exclusively by {@link
 *     LibraryDocumentService}.
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
 */
@ConfigurationProperties(prefix = "opaa.upload")
public record UploadProperties(
    String storagePath,
    long maxFileSize,
    ThreadPool threadPool,
    int pendingRecoveryThresholdMinutes) {

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
