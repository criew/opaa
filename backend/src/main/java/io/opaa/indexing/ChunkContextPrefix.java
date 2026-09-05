package io.opaa.indexing;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Kontextpräfix every chunk of a document carries into embedding and full-text index
 * (metadata-schema.md, Wirkstelle 2): {@code Titel › Fassung 2026 › § 7 Gebühren}. Contract: the
 * segments are title, the prefix-effective metadata values in schema order and the chunk's own
 * Strukturkontext; a blank segment is left out entirely, and a prefix without any segment does not
 * exist. The prefix is part of the chunk's presentation, never of its stored text - the quoted
 * excerpt in a Beleg stays the original wording.
 */
public final class ChunkContextPrefix {

  /** Separates the segments, as in the specification's own example. */
  public static final String SEPARATOR = " › ";

  /** The marker {@code ChunkLocationResolver} puts in front of a heading path in a Fundort. */
  private static final String SECTION_LOCATION_MARKER = "Abschn. ";

  private ChunkContextPrefix() {}

  /**
   * The prefix for one chunk, or {@code null} when no segment carries anything.
   *
   * @param structureContext the chunk's own section path, from {@link #structureContextFrom}
   */
  public static String build(String title, List<String> metadataValues, String structureContext) {
    List<String> segments = new ArrayList<>();
    addIfPresent(segments, title);
    if (metadataValues != null) {
      metadataValues.forEach(value -> addIfPresent(segments, value));
    }
    addIfPresent(segments, structureContext);
    return segments.isEmpty() ? null : String.join(SEPARATOR, segments);
  }

  /**
   * The embedding and full-text input of a chunk: its prefix in brackets, a blank line, the chunk
   * text. Unchanged from the title-only prefix of #933/#940, so a document whose prefix did not
   * change keeps a byte-identical input.
   */
  public static String format(String prefix, String chunkText) {
    return "[" + prefix + "]\n\n" + chunkText;
  }

  /**
   * The Strukturkontext segment derived from a chunk's Fundort: the heading path of a section, with
   * the {@code "Abschn. "} marker stripped. Two cases yield {@code null}: a page, slide or row
   * Fundort, which names no content and would only dilute both indexes, and a heading the chunk
   * text already opens with - a pipeline that cuts on headings keeps them in the text, and
   * repeating them in front of it adds nothing.
   */
  public static String structureContextFrom(Object location, String chunkText) {
    if (!(location instanceof String text) || !text.startsWith(SECTION_LOCATION_MARKER)) {
      return null;
    }
    String context = text.substring(SECTION_LOCATION_MARKER.length()).trim();
    if (context.isEmpty() || (chunkText != null && chunkText.startsWith(context))) {
      return null;
    }
    return context;
  }

  private static void addIfPresent(List<String> segments, String segment) {
    if (segment != null && !segment.isBlank()) {
      segments.add(segment.trim());
    }
  }
}
