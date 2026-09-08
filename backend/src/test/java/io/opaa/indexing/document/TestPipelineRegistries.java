package io.opaa.indexing.document;

import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.format.DocumentFormatRegistry;
import io.opaa.indexing.format.file.fallback.TikaFallbackFormat;
import java.util.List;

/**
 * Builds the registry the production wiring builds today - the Tika fallback pipeline and nothing
 * else - so a test that only cares about {@link DocumentIngestService} keeps stubbing {@link
 * DocumentService} and {@link ChunkingService} directly, exactly as before the pipeline abstraction
 * existed.
 *
 * <p>Public - consumed from {@code io.opaa.indexing.source.filesystem} test code; a test helper,
 * not a production API surface.
 */
public final class TestPipelineRegistries {

  private TestPipelineRegistries() {}

  public static DocumentFormatRegistry fallbackOnly(
      DocumentService documentService, ChunkingService chunkingService) {
    TikaFallbackFormat fallback = new TikaFallbackFormat(documentService, chunkingService);
    return new DocumentFormatRegistry(List.of(fallback), fallback);
  }

  /** The fallback plus the HTML pipeline - for the RSS entry path, which names it by id. */
  public static DocumentFormatRegistry fallbackAndHtml(
      DocumentService documentService, ChunkingService chunkingService) {
    TikaFallbackFormat fallback = new TikaFallbackFormat(documentService, chunkingService);
    return new DocumentFormatRegistry(
        List.of(fallback, new io.opaa.indexing.format.file.html.HtmlDocumentFormat()), fallback);
  }

  /** The fallback plus the Confluence page pipeline - for processConfluencePage tests. */
  public static DocumentFormatRegistry fallbackAndConfluence(
      DocumentService documentService, ChunkingService chunkingService) {
    TikaFallbackFormat fallback = new TikaFallbackFormat(documentService, chunkingService);
    return new DocumentFormatRegistry(
        List.of(
            fallback,
            new io.opaa.indexing.format.stream.confluencestorage.ConfluenceStorageFormat()),
        fallback);
  }
}
