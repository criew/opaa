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

  /**
   * The machine-readable position of the chunk in its original ({@link ChunkAnchor}), set by the
   * format and completed with its quotes by {@code DocumentFormatRunner}.
   */
  public static final String ANCHOR_METADATA_KEY = "anchor";

  /**
   * Transient: the text a chunk opens with that is not part of its own passage ({@link
   * ChunkAnchors#markLead}). Removed by {@code DocumentFormatRunner} before a chunk leaves the
   * format, so it is never stored.
   */
  public static final String ANCHOR_LEAD_METADATA_KEY = "anchor_lead";

  private ChunkMetadataKeys() {}
}
