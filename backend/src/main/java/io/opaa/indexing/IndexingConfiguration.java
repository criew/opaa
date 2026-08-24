package io.opaa.indexing;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.library.UploadProperties;
import io.opaa.observability.IndexingMetrics;
import java.time.Clock;
import java.util.List;
import org.springframework.ai.vectorstore.VectorStore;
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
      VectorStore vectorStore,
      ChecksumService checksumService,
      IndexingMetrics indexingMetrics,
      LibraryStorageQuotaService libraryStorageQuotaService,
      IndexingProperties indexingProperties,
      TaskExecutor embeddingTaskExecutor) {
    return new FileProcessingService(
        documentService,
        chunkingService,
        documentRepository,
        vectorStore,
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
   * Shared by every class fetching an {@code HTTP_DIRECTORY}/{@code RSS_FEED} target (#267) - a
   * single instance so the operator's configuration ({@code opaa.indexing.target-validation}) is
   * applied identically everywhere, mirroring {@link #filesystemPathAllowlist} above.
   */
  @Bean
  TargetAddressValidator targetAddressValidator(IndexingProperties properties) {
    return new TargetAddressValidator(properties.targetValidation());
  }

  // Declared as SourceIndexingExecutor, not the concrete executor type: both beans carry @Async
  // and are therefore wrapped in a JDK dynamic proxy at runtime, which only implements the
  // interfaces the target class declares - Spring could not inject the concrete type here even if
  // this method promised it. Nothing in this application injects AsyncIndexingExecutor or
  // UrlIndexingExecutor directly; every consumer (IndexingSourceExecutorRegistry) depends on
  // SourceIndexingExecutor already.
  @Bean
  SourceIndexingExecutor asyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      FilesystemPathAllowlist filesystemPathAllowlist,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService libraryStorageQuotaService,
      LibraryFolderService libraryFolderService) {
    return new AsyncIndexingExecutor(
        documentService,
        fileProcessingService,
        indexingJobService,
        filesystemPathAllowlist,
        indexingRunEventRepository,
        libraryStorageQuotaService,
        libraryFolderService);
  }

  @Bean
  AutoindexCrawlerService autoindexCrawlerService(TargetAddressValidator targetAddressValidator) {
    return new AutoindexCrawlerService(targetAddressValidator);
  }

  @Bean
  UrlFileDownloader urlFileDownloader(TargetAddressValidator targetAddressValidator) {
    return new UrlFileDownloader(targetAddressValidator);
  }

  @Bean
  SourceIndexingExecutor urlIndexingExecutor(
      AutoindexCrawlerService autoindexCrawlerService,
      UrlFileDownloader urlFileDownloader,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService libraryStorageQuotaService) {
    return new UrlIndexingExecutor(
        autoindexCrawlerService,
        urlFileDownloader,
        fileProcessingService,
        indexingJobService,
        documentRepository,
        indexingRunEventRepository,
        libraryStorageQuotaService);
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
      UrlFileDownloader urlFileDownloader,
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
        urlFileDownloader,
        properties,
        indexingRunEventRepository,
        targetAddressValidator,
        libraryStorageQuotaService);
  }

  /**
   * Populated from every {@link SourceIndexingExecutor} bean Spring finds (ADR-0017): a new source
   * type becomes reachable by adding one more bean here, never by editing this method or {@link
   * DocumentIndexingService}.
   */
  @Bean
  IndexingSourceExecutorRegistry indexingSourceExecutorRegistry(
      List<SourceIndexingExecutor> executors) {
    return new IndexingSourceExecutorRegistry(executors);
  }

  @Bean
  DocumentIndexingService documentIndexingService(
      IndexingJobService indexingJobService,
      IndexingSourceExecutorRegistry indexingSourceExecutorRegistry,
      UserRepository userRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      IndexingRunEventRepository indexingRunEventRepository) {
    return new DocumentIndexingService(
        indexingJobService,
        indexingSourceExecutorRegistry,
        userRepository,
        libraryRepository,
        libraryAccessService,
        indexingRunEventRepository);
  }

  /**
   * Backs every {@link SourceIndexingExecutor} (directory/URL/RSS indexing runs). Used to reject a
   * full queue with {@code AbortPolicy} (this class' default), not {@code
   * ThreadPoolExecutor.DiscardPolicy} (#501): a silently discarded task left its already-inserted
   * {@code indexing_jobs} row stuck at {@code RUNNING} forever - since #478 that locks the row's
   * one library out of every future trigger (409), with nothing in the UI to resolve it. {@code
   * AbortPolicy} throws {@link org.springframework.core.task.TaskRejectedException} synchronously
   * back to {@code DocumentIndexingService#triggerIndexing}, which catches it and fails the job
   * immediately instead of leaving it to rot - mirroring {@link #uploadTaskExecutor}'s own
   * reasoning for the same rejection handler.
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
   * Backs {@link FileProcessingService}'s concurrent embedding calls (#734,
   * opaa.indexing.embedding-concurrency). A single pool shared across every concurrent indexing run
   * in the process, not one per run or per library, sized to {@code embeddingConcurrency}.
   *
   * <p><b>This pool bounds only the sub-batch fan-out of a single document being split (#735
   * review, finding 4) - it is not an upper bound on every concurrent embedding call the process
   * makes.</b> A document whose chunks fit in one sub-batch (see {@link
   * FileProcessingService#subBatchSize}, e.g. {@code embeddingConcurrency <= 1}, or simply too few
   * chunks to split) never touches this pool at all - its single {@code vectorStore.add} call runs
   * directly on whichever thread called {@link FileProcessingService#storeChunks}, which is an
   * {@code indexing-} thread ({@link #indexingTaskExecutor}) for a connector run or an {@code
   * upload-} thread ({@link #uploadTaskExecutor}) for an upload. The actual number of embedding
   * calls that can be in flight across the whole process at once is therefore up to {@code
   * indexingTaskExecutor}'s pool size, plus {@code uploadTaskExecutor}'s pool size, plus this
   * pool's own {@code embeddingConcurrency} threads for whichever documents are currently split -
   * not {@code embeddingConcurrency} alone. An operator sizing a downstream embedding backend's own
   * concurrency limit needs the sum of all three, not just this property.
   *
   * <p>Fixed-size (core == max), mirroring {@link #uploadTaskExecutor}'s reasoning for a pool sized
   * to its own concurrency limit rather than left to grow - the queue capacity is deliberately
   * generous ({@link Integer#MAX_VALUE}, i.e. effectively unbounded) because the only thing ever
   * queued here is a document's own chunk sub-batches (bounded by that one document's chunk count),
   * never an unbounded external input - unlike {@link #indexingTaskExecutor}'s queue of whole
   * indexing runs, there is no equivalent "someone triggered too many runs" scenario to guard
   * against with {@code AbortPolicy} here.
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
   * Backs {@code FileProcessingService#processUploadedFileAsync} (#434) - deliberately a separate
   * pool from {@link #indexingTaskExecutor}, not a shared one (PR #589 review, finding 2), with its
   * own property block ({@link UploadProperties#threadPool}, #614) rather than reusing {@link
   * IndexingProperties#threadPool()} - see that property's Javadoc for why sharing the same values
   * would let one pool's sizing silently affect the other. Both executors share the same rejection
   * handling since #501: {@code ThreadPoolTaskExecutor}'s default {@code AbortPolicy} throws {@link
   * org.springframework.core.task.TaskRejectedException} synchronously back to the caller on a full
   * queue - {@code LibraryDocumentService#uploadDocument} turns it into an immediate {@code FAILED}
   * document row, {@code DocumentIndexingService#triggerIndexing} into an immediate {@code FAILED}
   * job row. Before #501, {@link #indexingTaskExecutor} used {@code
   * ThreadPoolExecutor.DiscardPolicy} instead, reasoning that a discarded run would simply be
   * retried by the next scheduled run - that silently left the already-inserted {@code
   * indexing_jobs} row stuck at {@code RUNNING} forever, which (since #478) locks the row's one
   * library out of every future trigger.
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
   * Server local time (#485) - the same "server time, no separate timezone configuration yet"
   * choice {@code io.opaa.audit.AuditRetentionScheduler}'s own {@code @Scheduled(cron = ...)}
   * already makes implicitly (Spring's default {@code @Scheduled} zone is the JVM's). A named
   * {@link Clock} bean, rather than {@code Clock.systemDefaultZone()} called directly in {@link
   * LibraryIndexingScheduler}, so a test can substitute a fixed clock without needing to control
   * wall-clock time.
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
