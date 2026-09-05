package io.opaa.indexing.pipeline;

/**
 * The two chunk metadata keys carrying the version of the verfahren that produced a chunk
 * (ingestion-pipelines.md, Querschnittsregel (d)). Written by {@code
 * FileProcessingService#storeChunks} onto every chunk and read back by {@code
 * io.opaa.indexing.PipelineReindexService} to select "every chunk below version N".
 *
 * <p>Deliberately chunk metadata rather than a column: {@code vector_store} is created by Spring AI
 * at startup and is not Liquibase-owned, and a value that is definitionally a property of the chunk
 * belongs on the chunk rather than in a third row per chunk.
 */
public final class ChunkPipelineMetadata {

  public static final String PIPELINE_ID_METADATA_KEY = "pipeline_id";
  public static final String PIPELINE_VERSION_METADATA_KEY = "pipeline_version";

  /**
   * What a chunk written before this metadata existed counts as. Not a guess: until the pipeline
   * abstraction existed, every chunk was produced by exactly the Tika reader plus token splitter
   * {@link TikaFallbackPipeline} still is. Attributed at {@link #LEGACY_PIPELINE_VERSION}, lower
   * than any real version, which is what makes it selectable for a re-index rather than opaque.
   */
  public static final String LEGACY_PIPELINE_ID = TikaFallbackPipeline.ID;

  /**
   * See {@link #LEGACY_PIPELINE_ID}. Below every {@link DocumentPipeline#version()}, which is 1+.
   */
  public static final int LEGACY_PIPELINE_VERSION = 0;

  /**
   * The extension {@link DocumentPipelineRegistry#routedPipelineFor} actually resolved when this
   * chunk was written, never the chunk's file name. Absent where routing could not be attempted or
   * completed, in which case {@code io.opaa.indexing.PipelineReindexService} falls back to its
   * file-name approximation instead of an exact comparison.
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
