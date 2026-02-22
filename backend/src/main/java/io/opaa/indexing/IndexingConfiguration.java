package io.opaa.indexing;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Activates all indexing-related beans only when NOT running in mock profile. This consolidates the
 * profile condition in a single place instead of scattering @Profile("!mock") on every service.
 */
@Configuration
@Profile("!mock")
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
  IndexingJobService indexingJobService(IndexingJobRepository indexingJobRepository) {
    return new IndexingJobService(indexingJobRepository);
  }

  @Bean
  FileProcessingService fileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorStore vectorStore) {
    return new FileProcessingService(
        documentService, chunkingService, documentRepository, vectorStore);
  }

  @Bean
  DocumentIndexingService documentIndexingService(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      IndexingProperties properties) {
    return new DocumentIndexingService(
        documentService, fileProcessingService, indexingJobService, properties);
  }
}
