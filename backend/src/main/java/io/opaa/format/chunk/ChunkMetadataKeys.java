package io.opaa.format.chunk;

/**
 * The chunk metadata keys a format writes for its readers. Search and chat read them back from the
 * stored chunk and depend on this holder alone, not on the cut that produces them.
 */
public final class ChunkMetadataKeys {

  /**
   * The human-readable Fundort ("S. 2–4 · Abschn. …"), set by {@link OverlappingTokenTextSplitter}
   * from {@link ChunkLocationResolver} or by a format's own splitter, copied onto the stored chunk
   * by {@code DocumentIngestService#storeChunks}.
   */
  public static final String LOCATION_METADATA_KEY = "location";

  private ChunkMetadataKeys() {}
}
