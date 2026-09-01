package io.opaa.indexing;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.library.UploadProperties;
import io.opaa.observability.IndexingMetrics;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class IndexingConfiguration {

  @Bean
  DocumentService documentService() {
    return new DocumentService();
  }

  @Bean
  ChunkingService chunkingService(IndexingProperties properties) {
    return new ChunkingService(properties);
  }

  @Bean
  ChecksumService checksumService() {
    return new ChecksumService();
  }

  @Bean
  IndexingJobService indexingJobService(IndexingJobRepository indexingJobRepository) {
    return new IndexingJobService(indexingJobRepository);
  }

  @Bean
  IndexingMetrics indexingMetrics(MeterRegistry meterRegistry) {
    return new IndexingMetrics(meterRegistry);
  }

  @Bean
  FileProcessingService fileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorChunkStore vectorChunkStore,
      ChecksumService checksumService,
      IndexingMetrics indexingMetrics,
      LibraryStorageQuotaService libraryStorageQuotaService,
      IndexingProperties indexingProperties,
      TaskExecutor embeddingTaskExecutor) {
    return new FileProcessingService(
        documentService,
        chunkingService,
        documentRepository,
        vectorChunkStore,
        checksumService,
        indexingMetrics,
        libraryStorageQuotaService,
        indexingProperties,
        embeddingTaskExecutor);
  }

  @Bean
  FilesystemPathAllowlist filesystemPathAllowlist(IndexingProperties properties) {
    return new FilesystemPathAllowlist(properties);
  }

  /**
   * Shared by every class fetching an {@code HTTP_DIRECTORY}/{@code RSS_FEED} target - a single
   * instance so the operator's configuration ({@code opaa.indexing.target-validation}) is applied
   * identically everywhere, mirroring {@link #filesystemPathAllowlist} above.
   */
  @Bean
  TargetAddressValidator targetAddressValidator(IndexingProperties properties) {
    return new TargetAddressValidator(
        properties.targetValidation().enabled(), properties.targetValidation().allowlist());
  }

  /**
   * Shared by every {@link SourceIndexingExecutor} bean below that runs a full, "vollständig
   * auflistend" crawl (FILESYSTEM, HTTP_DIRECTORY) - {@code RssFeedIndexingExecutor} deliberately
   * does not depend on this (#886, ADR-0017 decision 5).
   */
  @Bean
  StaleDocumentCleanupService staleDocumentCleanupService(
      DocumentRepository documentRepository, VectorChunkStore vectorChunkStore) {
    return new StaleDocumentCleanupService(documentRepository, vectorChunkStore);
  }

  // Declared as SourceIndexingExecutor, not the concrete executor type: all three beans below
  // carry @Async and are therefore wrapped in a JDK dynamic proxy at runtime, which only
  // implements the interfaces the target class declares. Every consumer
  // (IndexingSourceExecutorRegistry) depends on SourceIndexingExecutor already.
  @Bean
  SourceIndexingExecutor asyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      FilesystemPathAllowlist filesystemPathAllowlist,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService libraryStorageQuotaService,
      LibraryFolderService libraryFolderService,
      StaleDocumentCleanupService staleDocumentCleanupService) {
    return new AsyncIndexingExecutor(
        documentService,
        fileProcessingService,
        indexingJobService,
        filesystemPathAllowlist,
        indexingRunEventRepository,
        libraryStorageQuotaService,
        libraryFolderService,
        staleDocumentCleanupService);
  }

  @Bean
  AutoindexCrawlerService autoindexCrawlerService(
      TargetAddressValidator targetAddressValidator, CrawlProperties crawlProperties) {
    return new AutoindexCrawlerService(targetAddressValidator, crawlProperties);
  }

  @Bean
  BoundedDownloader boundedDownloader(TargetAddressValidator targetAddressValidator) {
    return new BoundedDownloader(targetAddressValidator);
  }

  @Bean
  SourceIndexingExecutor urlIndexingExecutor(
      AutoindexCrawlerService autoindexCrawlerService,
      BoundedDownloader boundedDownloader,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService libraryStorageQuotaService,
      StaleDocumentCleanupService staleDocumentCleanupService) {
    return new UrlIndexingExecutor(
        autoindexCrawlerService,
        boundedDownloader,
        fileProcessingService,
        indexingJobService,
        documentRepository,
        indexingRunEventRepository,
        libraryStorageQuotaService,
        staleDocumentCleanupService);
  }

  @Bean
  RssFeedParser rssFeedParser() {
    return new RssFeedParser();
  }

  @Bean
  SourceIndexingExecutor rssFeedIndexingExecutor(
      RssFeedParser rssFeedParser,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      RssFeedStateRepository rssFeedStateRepository,
      BoundedDownloader boundedDownloader,
      IndexingProperties properties,
      IndexingRunEventRepository indexingRunEventRepository,
      TargetAddressValidator targetAddressValidator,
      LibraryStorageQuotaService libraryStorageQuotaService) {
    return new RssFeedIndexingExecutor(
        rssFeedParser,
        fileProcessingService,
        indexingJobService,
        documentRepository,
        rssFeedStateRepository,
        boundedDownloader,
        properties,
        indexingRunEventRepository,
        targetAddressValidator,
        libraryStorageQuotaService);
  }

  /**
   * Populated from every {@link SourceIndexingExecutor} bean Spring finds: a new source type
   * becomes reachable by adding one more bean here, never by editing this method or {@link
   * DocumentIndexingService}.
   */
  @Bean
  IndexingSourceExecutorRegistry indexingSourceExecutorRegistry(
      List<SourceIndexingExecutor> executors) {
    return new IndexingSourceExecutorRegistry(executors);
  }

  @Bean
  LowChunkDocumentAuditService lowChunkDocumentAuditService(
      DocumentRepository documentRepository, KnowledgeLibraryRepository libraryRepository) {
    return new LowChunkDocumentAuditService(documentRepository, libraryRepository);
  }

  @Bean
  DocumentIndexingService documentIndexingService(
      IndexingJobService indexingJobService,
      IndexingSourceExecutorRegistry indexingSourceExecutorRegistry,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      IndexingRunEventRepository indexingRunEventRepository) {
    return new DocumentIndexingService(
        indexingJobService,
        indexingSourceExecutorRegistry,
        libraryRepository,
        libraryAccessService,
        indexingRunEventRepository);
  }

  /**
   * Backs every {@link SourceIndexingExecutor} (directory/URL/RSS indexing runs). Used to reject a
   * full queue with {@code AbortPolicy} (this class' default), not {@code
   * ThreadPoolExecutor.DiscardPolicy}: a silently discarded task would leave its already-inserted
   * {@code indexing_jobs} row stuck at {@code RUNNING} forever, locking the row's one library out
   * of every future trigger (409). {@code AbortPolicy} throws {@link
   * org.springframework.core.task.TaskRejectedException} synchronously back to {@code
   * DocumentIndexingService#triggerIndexing}, which catches it and fails the job immediately -
   * mirroring {@link #uploadTaskExecutor}'s own reasoning.
   */
  @Bean
  TaskExecutor indexingTaskExecutor(IndexingProperties properties) {
    IndexingProperties.ThreadPool pool = properties.threadPool();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.coreSize());
    executor.setMaxPoolSize(pool.maxSize());
    executor.setQueueCapacity(pool.queueCapacity());
    executor.setThreadNamePrefix("indexing-");
    executor.initialize();
    return executor;
  }

  /**
   * Backs {@link FileProcessingService}'s concurrent embedding calls
   * (opaa.indexing.embedding-concurrency). A single pool shared across every concurrent indexing
   * run in the process, not one per run or per library, sized to {@code embeddingConcurrency}.
   *
   * <p>This pool bounds only the sub-batch fan-out of a single document being split - it is not an
   * upper bound on every concurrent embedding call the process makes. A document whose chunks fit
   * in one sub-batch (see {@link FileProcessingService#subBatchSize}) never touches this pool at
   * all - its single {@code vectorStore.add} call runs directly on whichever thread called {@link
   * FileProcessingService#storeChunks}, an {@code indexing-} thread ({@link #indexingTaskExecutor})
   * for a connector run or an {@code upload-} thread ({@link #uploadTaskExecutor}) for an upload.
   * The actual number of embedding calls in flight across the whole process is therefore up to
   * {@code indexingTaskExecutor}'s pool size, plus {@code uploadTaskExecutor}'s pool size, plus
   * this pool's own {@code embeddingConcurrency} threads - not {@code embeddingConcurrency} alone.
   *
   * <p>Fixed-size (core == max), mirroring {@link #uploadTaskExecutor}'s reasoning - the queue
   * capacity is deliberately generous ({@link Integer#MAX_VALUE}) because the only thing ever
   * queued here is a document's own chunk sub-batches, never an unbounded external input, unlike
   * {@link #indexingTaskExecutor}'s queue of whole indexing runs.
   */
  @Bean
  TaskExecutor embeddingTaskExecutor(IndexingProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.embeddingConcurrency());
    executor.setMaxPoolSize(properties.embeddingConcurrency());
    executor.setQueueCapacity(Integer.MAX_VALUE);
    executor.setThreadNamePrefix("embedding-");
    executor.initialize();
    return executor;
  }

  /**
   * Backs {@code FileProcessingService#processUploadedFileAsync} - deliberately a separate pool
   * from {@link #indexingTaskExecutor}, not a shared one, with its own property block ({@link
   * UploadProperties#threadPool}) rather than reusing {@link IndexingProperties#threadPool()}. Both
   * executors share the same rejection handling: {@code ThreadPoolTaskExecutor}'s default {@code
   * AbortPolicy} throws {@link org.springframework.core.task.TaskRejectedException} synchronously
   * back to the caller on a full queue - {@code LibraryDocumentService#uploadDocument} turns it
   * into an immediate {@code FAILED} document row, {@code DocumentIndexingService#triggerIndexing}
   * into an immediate {@code FAILED} job row.
   */
  @Bean
  TaskExecutor uploadTaskExecutor(UploadProperties properties) {
    UploadProperties.ThreadPool pool = properties.threadPool();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.coreSize());
    executor.setMaxPoolSize(pool.maxSize());
    executor.setQueueCapacity(pool.queueCapacity());
    executor.setThreadNamePrefix("upload-");
    executor.initialize();
    return executor;
  }

  /**
   * Server local time - the same choice {@code io.opaa.audit.AuditRetentionScheduler}'s own
   * {@code @Scheduled(cron = ...)} already makes implicitly. A named {@link Clock} bean, rather
   * than {@code Clock.systemDefaultZone()} called directly in {@link LibraryIndexingScheduler}, so
   * a test can substitute a fixed clock.
   */
  @Bean
  Clock schedulingClock() {
    return Clock.systemDefaultZone();
  }

  @Bean
  LibraryIndexingScheduler libraryIndexingScheduler(
      KnowledgeLibraryRepository libraryRepository,
      DocumentIndexingService documentIndexingService,
      IndexingJobService indexingJobService,
      IndexingRunEventRepository indexingRunEventRepository,
      Clock schedulingClock) {
    return new LibraryIndexingScheduler(
        libraryRepository,
        documentIndexingService,
        indexingJobService,
        indexingRunEventRepository,
        schedulingClock);
  }
}
