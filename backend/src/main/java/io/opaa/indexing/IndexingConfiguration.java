package io.opaa.indexing;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.observability.IndexingMetrics;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
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
      IndexingMetrics indexingMetrics) {
    return new FileProcessingService(
        documentService,
        chunkingService,
        documentRepository,
        vectorStore,
        checksumService,
        indexingMetrics);
  }

  // Declared as SourceIndexingExecutor, not the concrete executor type: both beans carry @Async
  // and are therefore wrapped in a JDK dynamic proxy at runtime, which only implements the
  // interfaces the target class declares - Spring could not inject the concrete type here even if
  // this method promised it. Nothing in this application injects AsyncIndexingExecutor or
  // UrlIndexingExecutor directly; every consumer (IndexingSourceExecutorRegistry) depends on
  // SourceIndexingExecutor already.
  @Bean
  FilesystemPathAllowlist filesystemPathAllowlist(IndexingProperties properties) {
    return new FilesystemPathAllowlist(properties);
  }

  @Bean
  SourceIndexingExecutor asyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      FilesystemPathAllowlist filesystemPathAllowlist,
      IndexingRunEventRepository indexingRunEventRepository) {
    return new AsyncIndexingExecutor(
        documentService,
        fileProcessingService,
        indexingJobService,
        filesystemPathAllowlist,
        indexingRunEventRepository);
  }

  @Bean
  AutoindexCrawlerService autoindexCrawlerService() {
    return new AutoindexCrawlerService();
  }

  @Bean
  UrlFileDownloader urlFileDownloader() {
    return new UrlFileDownloader();
  }

  @Bean
  SourceIndexingExecutor urlIndexingExecutor(
      AutoindexCrawlerService autoindexCrawlerService,
      UrlFileDownloader urlFileDownloader,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository) {
    return new UrlIndexingExecutor(
        autoindexCrawlerService,
        urlFileDownloader,
        fileProcessingService,
        indexingJobService,
        documentRepository,
        indexingRunEventRepository);
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
      IndexingRunEventRepository indexingRunEventRepository) {
    return new RssFeedIndexingExecutor(
        rssFeedParser,
        fileProcessingService,
        indexingJobService,
        documentRepository,
        rssFeedStateRepository,
        urlFileDownloader,
        properties,
        indexingRunEventRepository);
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

  @Bean
  TaskExecutor indexingTaskExecutor(IndexingProperties properties) {
    IndexingProperties.ThreadPool pool = properties.threadPool();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.coreSize());
    executor.setMaxPoolSize(pool.maxSize());
    executor.setQueueCapacity(pool.queueCapacity());
    executor.setThreadNamePrefix("indexing-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * Backs {@code FileProcessingService#processUploadedFileAsync} (#434) - deliberately a separate
   * pool from {@link #indexingTaskExecutor}, not a shared one (PR #589 review, finding 2).
   * Directory/URL indexing discards a task outright when its queue is full ({@code
   * ThreadPoolExecutor.DiscardPolicy} above) - fine there, since the next scheduled run picks up
   * whatever was skipped. An interactively uploaded document has no such follow-up run: a silently
   * discarded task would leave its row stuck at {@code PENDING} forever, polled endlessly by the
   * frontend with nothing to explain why. This executor keeps {@code ThreadPoolTaskExecutor}'s own
   * default rejection handler ({@code AbortPolicy}) instead, so a full queue throws {@link
   * org.springframework.core.task.TaskRejectedException} synchronously back to {@code
   * LibraryDocumentService#uploadDocument}, which turns it into an immediate {@code FAILED} row.
   */
  @Bean
  TaskExecutor uploadTaskExecutor(IndexingProperties properties) {
    IndexingProperties.ThreadPool pool = properties.threadPool();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.coreSize());
    executor.setMaxPoolSize(pool.maxSize());
    executor.setQueueCapacity(pool.queueCapacity());
    executor.setThreadNamePrefix("upload-");
    executor.initialize();
    return executor;
  }
}
