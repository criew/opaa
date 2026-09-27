package io.opaa.indexing.chunk;

/**
 * The chunk metadata keys naming where a document sits in its source, with the same values as the
 * document's own columns of those names. {@code DocumentIngestService#storeChunks} writes them onto
 * every chunk of a document whose source declared them, independent of the format that cut it.
 */
public final class SourceChunkMetadataKeys {

  /** The container a document came from; source-neutral by name and by value. */
  public static final String SOURCE_CONTAINER_METADATA_KEY = "source_container_key";

  /**
   * The document's ancestors root first, joined with " / ". Written independently of {@link
   * #SOURCE_CONTAINER_METADATA_KEY}, so a document at the root of its container carries the
   * container key alone.
   */
  public static final String SOURCE_HIERARCHY_METADATA_KEY = "source_hierarchy_path";

  private SourceChunkMetadataKeys() {}
}
