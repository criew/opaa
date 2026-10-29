package io.opaa.format.chunk;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes and completes the {@link ChunkAnchor} on a chunk's metadata. A format sets the structural
 * units it knows ({@link #write}) and, where a chunk opens with text that is not part of the
 * passage itself - a repeated heading line, a slide title, an overlap carried over from the
 * predecessor - names that lead ({@link #markLead}). {@code DocumentFormatRunner} then derives the
 * quotes from the chunk's final text without the lead ({@link #completed}) and drops the lead key,
 * so it never reaches a stored chunk.
 */
public final class ChunkAnchors {

  /** Upper bound of each quote, in characters after whitespace normalization. */
  public static final int QUOTE_LENGTH = 60;

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private ChunkAnchors() {}

  /** The anchor {@code metadata} carries, {@link ChunkAnchor#EMPTY} without one. */
  public static ChunkAnchor read(Map<String, Object> metadata) {
    return ChunkAnchor.fromMetadata(metadata.get(ChunkMetadataKeys.ANCHOR_METADATA_KEY));
  }

  /** Stores {@code anchor} on {@code metadata}, or removes the key when it is empty. */
  public static void write(Map<String, Object> metadata, ChunkAnchor anchor) {
    if (anchor.isEmpty()) {
      metadata.remove(ChunkMetadataKeys.ANCHOR_METADATA_KEY);
    } else {
      metadata.put(ChunkMetadataKeys.ANCHOR_METADATA_KEY, anchor.toMetadata());
    }
  }

  /**
   * Records that every chunk built from {@code metadata} whose text opens with {@code lead} has
   * that lead in front of its own passage. A part of a split chunk that does not open with it keeps
   * its whole text as the passage.
   */
  public static void markLead(Map<String, Object> metadata, String lead) {
    if (lead != null && !lead.isEmpty()) {
      metadata.put(ChunkMetadataKeys.ANCHOR_LEAD_METADATA_KEY, lead);
    }
  }

  /**
   * {@code text} without {@code lead} when it opens with it, else {@code text} itself - the part of
   * a chunk the quotes are taken from.
   */
  public static String passage(String text, Object lead) {
    if (text == null) {
      return "";
    }
    if (lead instanceof String prefix && !prefix.isEmpty() && text.startsWith(prefix)) {
      String rest = text.substring(prefix.length());
      return rest.isBlank() ? text : rest;
    }
    return text;
  }

  /** {@code anchor} with both quotes taken from {@code passage}. */
  public static ChunkAnchor withQuotes(ChunkAnchor anchor, String passage) {
    String normalized = normalized(passage);
    if (normalized.isEmpty()) {
      return anchor.withQuotes(null, null);
    }
    if (normalized.length() <= QUOTE_LENGTH) {
      return anchor.withQuotes(normalized, null);
    }
    return anchor.withQuotes(head(normalized), tail(normalized));
  }

  /**
   * The metadata of a chunk whose final text is {@code text}: the anchor completed with its quotes,
   * the lead key removed. Every chunk with text leaves with an anchor.
   */
  public static void completed(Map<String, Object> metadata, String text) {
    Object lead = metadata.remove(ChunkMetadataKeys.ANCHOR_LEAD_METADATA_KEY);
    write(metadata, withQuotes(read(metadata), passage(text, lead)));
  }

  /** Whitespace runs collapsed to one space, ends stripped - how a viewer compares text as well. */
  public static String normalized(String text) {
    return text == null ? "" : WHITESPACE.matcher(text).replaceAll(" ").strip();
  }

  private static String head(String text) {
    int cut = text.lastIndexOf(' ', QUOTE_LENGTH);
    return text.substring(0, cut > QUOTE_LENGTH / 2 ? cut : QUOTE_LENGTH);
  }

  private static String tail(String text) {
    int from = text.length() - QUOTE_LENGTH;
    int cut = text.indexOf(' ', from);
    return text.substring(cut >= 0 && cut < from + QUOTE_LENGTH / 2 ? cut + 1 : from);
  }
}
