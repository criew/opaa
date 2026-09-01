package io.opaa.indexing;

import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import java.util.List;

/**
 * Builds the registry the production wiring builds today - the Tika fallback pipeline and nothing
 * else - so a test that only cares about {@link FileProcessingService} keeps stubbing {@link
 * DocumentService} and {@link ChunkingService} directly, exactly as before the pipeline abstraction
 * existed.
 */
final class TestPipelineRegistries {

  private TestPipelineRegistries() {}

  static DocumentPipelineRegistry fallbackOnly(
      DocumentService documentService, ChunkingService chunkingService) {
    TikaFallbackPipeline fallback = new TikaFallbackPipeline(documentService, chunkingService);
    return new DocumentPipelineRegistry(List.of(fallback), fallback);
  }
}
