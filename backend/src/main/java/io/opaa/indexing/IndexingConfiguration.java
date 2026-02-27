package io.opaa.indexing;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Activates all indexing-related beans only when NOT running in mock profile. This consolidates the
 * profile condition in a single place instead of scattering @Profile("!mock") on every service.
 */
@Configuration
@Profile("!mock")
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
  FileProcessingService fileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorStore vectorStore,
      ChecksumService checksumService) {
    return new FileProcessingService(
        documentService, chunkingService, documentRepository, vectorStore, checksumService);
  }

  @Bean
  AsyncIndexingExecutor asyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      IndexingProperties properties) {
    return new AsyncIndexingExecutor(
        documentService, fileProcessingService, indexingJobService, properties);
  }

  @Bean
  DocumentIndexingService documentIndexingService(
      IndexingJobService indexingJobService, AsyncIndexingExecutor asyncIndexingExecutor) {
    return new DocumentIndexingService(indexingJobService, asyncIndexingExecutor);
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
}
