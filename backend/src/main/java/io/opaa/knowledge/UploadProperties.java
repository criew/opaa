package io.opaa.knowledge;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.NoDataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the REST document upload endpoint (docs/features/knowledge-sources.md#upload).
 *
 * @param storagePath filesystem path where uploaded files are stored, as {@code
 *     <storagePath>/<organizationId>/<libraryId>/<uuid><extension>} so files of different tenants
 *     and libraries never collide and every file names its own organization without a database row
 *     being read. Deliberately separate from a FILESYSTEM library's own {@code sourcePath}
 *     (ADR-0018): that directory is crawled by the filesystem indexing path and is
 *     operator-managed, whereas this one is written to exclusively by {@code
 *     LibraryDocumentService}. Read by {@link FilesystemUploadedOriginalStore} alone (ADR-0030).
 * @param store which storage backend holds the uploaded originals (ADR-0030, Entscheidung 1):
 *     {@code filesystem} (the default) or {@code s3} ({@link UploadS3Properties}); {@link
 *     UploadStorageConfiguration} refuses the start on any other value.
 * @param maxFileSize maximum accepted upload size in bytes. Default 50 MiB (52 428 800): generous
 *     enough for a typical scanned Dienstanweisung while still bounding memory and disk use per
 *     upload; a larger file is refused with 413.
 * @param threadPool thread pool settings for {@code IndexingConfiguration#uploadTaskExecutor}.
 *     Deliberately its own property block, not a reuse of {@code opaa.indexing.thread-pool}: {@code
 *     uploadTaskExecutor} and {@code indexingTaskExecutor} are two independent {@code
 *     ThreadPoolTaskExecutor} beans - an operator raising {@code
 *     opaa.indexing.thread-pool.max-size} to cap total indexing concurrency would, while both
 *     executors read the same properties, actually double it instead, since the upload pool grew by
 *     the same amount unnoticed. Defaults (core=2, max=4, queue=20) mirror {@code
 *     opaa.indexing.thread-pool}'s own conservative defaults.
 * @param pendingRecoveryThresholdMinutes how long a document may stay {@code PENDING} before {@code
 *     UploadPendingRecoveryRunner} treats it as abandoned by a process that died mid-task and sets
 *     it to {@code FAILED} at the next application startup. Default 30 minutes: comfortably longer
 *     than parsing/embedding even a large upload should ever take, so a row is only ever caught
 *     here because nothing is actually still working on it.
 * @param orphanGraceMinutes how old a stored original must be before {@code
 *     OrphanedOriginalCleanupService} may report or remove it as orphaned (ADR-0030,
 *     "Konsequenzen"): the window between storing the bytes and inserting the row, and the whole
 *     asynchronous processing of an upload, must lie inside it. Default 60 minutes - twice {@link
 *     #pendingRecoveryThresholdMinutes}, so an upload still legitimately in flight is never
 *     mistaken for an orphan. A report may raise it per call, never lower it.
 */
@NoDataImpact("sizes, pools and recovery of uploads")
@ConfigurationProperties(prefix = "opaa.upload")
public record UploadProperties(
    String storagePath,
    @DataImpact String store,
    long maxFileSize,
    ThreadPool threadPool,
    @NoDataImpact("marks an abandoned upload failed; it deletes nothing")
        int pendingRecoveryThresholdMinutes,
    @DataImpact int orphanGraceMinutes) {

  public UploadProperties {
    if (storagePath == null || storagePath.isBlank()) {
      storagePath = "./uploads";
    }
    if (store == null || store.isBlank()) {
      store = FilesystemUploadedOriginalStore.STORE_NAME;
    }
    maxFileSize = positiveOr(maxFileSize, 50L * 1024 * 1024);
    if (threadPool == null) {
      threadPool = new ThreadPool(2, 4, 20);
    }
    pendingRecoveryThresholdMinutes = positiveOr(pendingRecoveryThresholdMinutes, 30);
    orphanGraceMinutes = positiveOr(orphanGraceMinutes, 60);
  }

  /**
   * The executor of uploads; mirrors {@code IndexingProperties.ThreadPool}'s own validation.
   *
   * @param coreSize threads kept alive
   * @param maxSize threads at most
   * @param queueCapacity uploads waiting for a thread before a new one is refused
   */
  public record ThreadPool(int coreSize, int maxSize, int queueCapacity) {

    public ThreadPool {
      coreSize = positiveOr(coreSize, 2);
      maxSize = positiveOr(maxSize, 4);
      if (queueCapacity < 0) {
        queueCapacity = 20;
      }
    }
  }
}
