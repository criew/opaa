package io.opaa.indexing.document;

import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.BoundarySplitter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Halves a chunk the embedding endpoint proved too long, at the coarsest text boundaries {@link
 * BoundarySplitter} finds. Every part carries a copy of the chunk's metadata and so its Fundort; a
 * chunk opening with the heading path its Fundort names repeats that line in front of every part.
 * Each part's anchor quotes that part's own text, the first part's without the chunk's lead.
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
    List<Document> result = new ArrayList<>(parts.size());
    for (int i = 0; i < parts.size(); i++) {
      String passage = i == 0 ? passageOfFirst(parts.get(0), anchor) : parts.get(i);
      result.add(part(chunk, heading, parts.get(i), passage, anchor));
    }
    return result;
  }

  /** One part: the chunk's metadata, its anchor quoting {@code passage}, this part's own text. */
  private static Document part(
      Document chunk, String heading, String part, String passage, ChunkAnchor anchor) {
    Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
    if (!anchor.isEmpty()) {
      ChunkAnchors.write(metadata, ChunkAnchors.withQuotes(anchor, passage));
    }
    return new Document(heading == null ? part : heading + HEADING_SEPARATOR + part, metadata);
  }

  /**
   * The first part without the lead the chunk opened with (table header, slide title, overlap): the
   * chunk's {@code quoteStart} already excludes it, so the passage starts where that quote does.
   * Without a quote, or when it is not found, the whole part.
   */
  private static String passageOfFirst(String part, ChunkAnchor anchor) {
    String normalized = ChunkAnchors.normalized(part);
    if (anchor.quoteStart() == null) {
      return normalized;
    }
    int start = normalized.indexOf(anchor.quoteStart());
    return start < 0 ? normalized : normalized.substring(start);
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
