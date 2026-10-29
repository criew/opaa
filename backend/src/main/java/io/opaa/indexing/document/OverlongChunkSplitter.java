package io.opaa.indexing.document;

import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.BoundarySplitter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Halves a chunk the embedding endpoint proved too long, at the coarsest text boundaries {@link
 * BoundarySplitter} finds. Every part carries a copy of the chunk's metadata and so its Fundort; a
 * chunk opening with the heading path its Fundort names repeats that line in front of every part.
 * Each part's anchor quotes that part's own text.
 */
final class OverlongChunkSplitter {

  /**
   * The marker in front of a heading path in a Fundort, as {@code ChunkLocationResolver} writes.
   */
  private static final String SECTION_LOCATION_MARKER = "Abschn. ";

  private static final String HEADING_SEPARATOR = "\n\n";

  private OverlongChunkSplitter() {}

  /** The parts of {@code chunk}, or {@code chunk} alone when its text cannot be split further. */
  static List<Document> split(Document chunk) {
    String text = chunk.getText() == null ? "" : chunk.getText();
    String heading = leadingHeading(chunk, text);
    String body = heading == null ? text : text.substring(heading.length()).strip();
    if (body.length() < 2) {
      return List.of(chunk);
    }
    List<String> parts = BoundarySplitter.split(body, (body.length() + 1) / 2);
    ChunkAnchor anchor = ChunkAnchors.read(chunk.getMetadata());
    return parts.stream().map(part -> part(chunk, heading, part, anchor)).toList();
  }

  /** One part: the chunk's metadata, its anchor quoting this part alone. */
  private static Document part(Document chunk, String heading, String part, ChunkAnchor anchor) {
    Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
    if (!anchor.isEmpty()) {
      ChunkAnchors.write(metadata, ChunkAnchors.withQuotes(anchor, part));
    }
    return new Document(heading == null ? part : heading + HEADING_SEPARATOR + part, metadata);
  }

  /** The heading path of a section Fundort when the chunk text opens with it, else {@code null}. */
  private static String leadingHeading(Document chunk, String text) {
    if (!(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY)
            instanceof String location)
        || !location.startsWith(SECTION_LOCATION_MARKER)) {
      return null;
    }
    String path = location.substring(SECTION_LOCATION_MARKER.length()).strip();
    return !path.isEmpty() && text.startsWith(path + HEADING_SEPARATOR) ? path : null;
  }
}
