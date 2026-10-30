package io.opaa.search;

import io.opaa.format.chunk.ChunkAnchor;
import java.util.List;
import java.util.UUID;

/**
 * One window of a document's Textfassung: its passages in index order, each without the part it
 * shares with its predecessor, plus the document's index bounds and, when asked for, its outline.
 * The bounds are null when nothing of the document is indexed.
 */
public record DocumentText(
    UUID documentId,
    String fileName,
    Integer firstChunkIndex,
    Integer lastChunkIndex,
    List<Passage> passages,
    List<DocumentOutline.Section> sections,
    boolean sectionsTruncated) {

  /** One passage; {@code anchor} is null for a chunk stored without one. */
  public record Passage(int chunkIndex, String text, String location, ChunkAnchor anchor) {}
}
