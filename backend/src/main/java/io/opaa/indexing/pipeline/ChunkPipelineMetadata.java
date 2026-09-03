package io.opaa.indexing.pipeline;

/**
 * The two chunk metadata keys carrying the version of the verfahren that produced a chunk
 * (docs/features/ingestion-pipelines.md, cross-cutting rule (d)). Written by {@code
 * FileProcessingService#storeChunks} onto every chunk, alongside the existing technical metadata,
 * and read back by {@code io.opaa.indexing.PipelineReindexService} to select "every chunk below
 * version N of this pipeline".
 *
 * <p>Deliberately chunk metadata in {@code vector_store}, not a column in a second table: {@code
 * vector_store} is created by Spring AI at application startup and is not Liquibase-owned (see
 * {@code changes/003-chunk-full-text-table.yaml}'s own comment), so a column on it is not
 * available; and a value that is definitionally a property of the chunk belongs on the chunk rather
 * than in a third row per chunk.
 */
public final class ChunkPipelineMetadata {

  public static final String PIPELINE_ID_METADATA_KEY = "pipeline_id";
  public static final String PIPELINE_VERSION_METADATA_KEY = "pipeline_version";

  /**
   * What a chunk written before this metadata existed counts as. Not a guess: until the pipeline
   * abstraction existed, every chunk in every corpus was produced by exactly the Tika reader plus
   * token splitter that {@link TikaFallbackPipeline} still is, so the pre-existing corpus is
   * attributed to that pipeline at version {@link #LEGACY_PIPELINE_VERSION} - lower than any
   * pipeline's real version, which is what makes it selectable for a re-index instead of an opaque
   * "unknown".
   */
  public static final String LEGACY_PIPELINE_ID = TikaFallbackPipeline.ID;

  /**
   * See {@link #LEGACY_PIPELINE_ID}. Below every {@link DocumentPipeline#version()}, which is 1+.
   */
  public static final int LEGACY_PIPELINE_VERSION = 0;

  /**
   * The extension {@link DocumentPipelineRegistry#routedPipelineFor} actually resolved when this
   * chunk was written (#1126), never the chunk's file name. Absent for a chunk where routing could
   * not be attempted or completed (the pre-#1126 Altbestand; a failed detection) - {@code
   * io.opaa.indexing.PipelineReindexService} then falls back to its pre-#1126 file-name
   * approximation instead of the exact comparison {@link
   * DocumentPipelineRegistry#pipelineIdForRoutingExtension(String)} gives.
   */
  public static final String ROUTING_EXTENSION_METADATA_KEY = "routing_extension";

  /**
   * The sentinel {@link #ROUTING_EXTENSION_METADATA_KEY} carries when routing resolved no extension
   * at all (content admits nothing) - distinct from the key's absence. Required, not merely chosen:
   * {@code org.springframework.ai.document.Document}'s constructor asserts no metadata value is
   * {@code null} (spring-ai-commons, {@code Assert.noNullElements}), so {@code null} itself could
   * never be written.
   */
  public static final String NO_ROUTING_EXTENSION = "";

  private ChunkPipelineMetadata() {}
}
