package io.opaa.indexing;

import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import java.util.List;

/**
 * Builds the registry the production wiring builds today - the Tika fallback pipeline and nothing
 * else - so a test that only cares about {@link FileProcessingService} keeps stubbing {@link
 * DocumentService} and {@link ChunkingService} directly, exactly as before the pipeline abstraction
 * existed.
 *
 * <p>Public - consumed from {@code io.opaa.indexing.source.filesystem} test code (#1113); a test
 * helper, not a production API surface.
 */
public final class TestPipelineRegistries {

  private TestPipelineRegistries() {}

  public static DocumentPipelineRegistry fallbackOnly(
      DocumentService documentService, ChunkingService chunkingService) {
    TikaFallbackPipeline fallback = new TikaFallbackPipeline(documentService, chunkingService);
    return new DocumentPipelineRegistry(List.of(fallback), fallback);
  }

  /** The fallback plus the Confluence page pipeline (#1137) - for processConfluencePage tests. */
  public static DocumentPipelineRegistry fallbackAndConfluence(
      DocumentService documentService, ChunkingService chunkingService) {
    TikaFallbackPipeline fallback = new TikaFallbackPipeline(documentService, chunkingService);
    return new DocumentPipelineRegistry(
        List.of(fallback, new io.opaa.indexing.pipeline.confluence.ConfluenceDocumentPipeline()),
        fallback);
  }
}
