package io.opaa.indexing.pipeline;

/**
 * The two chunk metadata keys carrying the version of the verfahren that produced a chunk
 * (docs/features/ingestion-pipelines.md, cross-cutting rule (d)). Written by {@code
 * FileProcessingService#storeChunks} onto every chunk, alongside the existing technical metadata,
 * and read back by {@link PipelineReindexService} to select "every chunk below version N of this
 * pipeline".
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
   * chunk was written - {@code null} exactly when routing fell back without resolving one (see
   * {@link DocumentPipelineRegistry.Routed#detectedExtension()}), never the chunk's file name.
   * Written by {@code FileProcessingService#storeChunks} on every chunk since #1126, alongside
   * {@link #PIPELINE_ID_METADATA_KEY}.
   *
   * <p>Replaces the endung-based approximation {@link PipelineReindexService} used before #1126
   * (still the fallback for a chunk written before this key existed, via {@code
   * currentPipelineIdForFileName}) with an exact comparison: {@link
   * DocumentPipelineRegistry#pipelineIdForRoutingExtension(String)} on this stored value tells
   * exactly which pipeline claims the format this chunk was actually routed on, without guessing
   * from the file name. A chunk without this key is the pre-#1126 Altbestand and stays on the
   * approximation - {@link #NO_ROUTING_EXTENSION} is what a forward-written chunk gets instead of
   * {@code null} itself (a {@code jsonb} value cannot distinguish an absent key from one explicitly
   * set to {@code null}), so "never written" and "written, resolved to no extension" stay
   * distinguishable.
   */
  public static final String ROUTING_EXTENSION_METADATA_KEY = "routing_extension";

  /**
   * The sentinel {@link #ROUTING_EXTENSION_METADATA_KEY} carries for a chunk whose routing decision
   * did not resolve an extension at all - content that fell back without a strict or text-tolerant
   * match (e.g. a file named like a strict format but whose actual content is plain text). See that
   * key's own Javadoc for why this is not simply the key's absence.
   */
  public static final String NO_ROUTING_EXTENSION = "";

  private ChunkPipelineMetadata() {}
}
