package io.opaa.format.chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a chunk sits in its original, machine-readable: the structural unit a viewer jumps to
 * (pages, heading path, slide, sheet and rows, thread message) plus two short excerpts of the
 * chunk's own text a viewer searches to mark the passage. Every component is optional; a format
 * fills only the units its documents have, and {@link #quoteStart} is the one every chunk with text
 * carries.
 *
 * <p>Stored on the chunk under {@link ChunkMetadataKeys#ANCHOR_METADATA_KEY} as a sparse map of
 * exactly these component names ({@link #toMetadata}, {@link #fromMetadata}), and persisted with a
 * chat's sources as a snapshot of the cited passage.
 *
 * @param pageFrom first page, 1-based
 * @param pageTo last page, 1-based, inclusive
 * @param path the full heading path the chunk belongs to, outermost first
 * @param slide slide number, 1-based
 * @param sheet worksheet name of a spreadsheet, absent for a single-table file such as CSV
 * @param rowFrom first source row number, as the spreadsheet numbers it
 * @param rowTo last source row number, inclusive
 * @param message 1-based position of the message within a mail thread
 * @param quoteStart the chunk's own text from its start, whitespace-normalized, up to {@link
 *     ChunkAnchors#QUOTE_LENGTH} characters cut at a word boundary
 * @param quoteEnd the same from its end; absent when the whole text fits {@code quoteStart}
 */
public record ChunkAnchor(
    Integer pageFrom,
    Integer pageTo,
    List<String> path,
    Integer slide,
    String sheet,
    Long rowFrom,
    Long rowTo,
    Integer message,
    String quoteStart,
    String quoteEnd) {

  public static final ChunkAnchor EMPTY =
      new ChunkAnchor(null, null, null, null, null, null, null, null, null, null);

  public ChunkAnchor {
    path = path == null || path.isEmpty() ? null : List.copyOf(path);
  }

  public ChunkAnchor withPages(int from, int to) {
    return new ChunkAnchor(
        from, to, path, slide, sheet, rowFrom, rowTo, message, quoteStart, quoteEnd);
  }

  public ChunkAnchor withPath(List<String> path) {
    return new ChunkAnchor(
        pageFrom, pageTo, path, slide, sheet, rowFrom, rowTo, message, quoteStart, quoteEnd);
  }

  public ChunkAnchor withSlide(int slide) {
    return new ChunkAnchor(
        pageFrom, pageTo, path, slide, sheet, rowFrom, rowTo, message, quoteStart, quoteEnd);
  }

  public ChunkAnchor withRows(String sheet, long from, long to) {
    return new ChunkAnchor(
        pageFrom, pageTo, path, slide, sheet, from, to, message, quoteStart, quoteEnd);
  }

  public ChunkAnchor withMessage(int message) {
    return new ChunkAnchor(
        pageFrom, pageTo, path, slide, sheet, rowFrom, rowTo, message, quoteStart, quoteEnd);
  }

  public ChunkAnchor withQuotes(String quoteStart, String quoteEnd) {
    return new ChunkAnchor(
        pageFrom, pageTo, path, slide, sheet, rowFrom, rowTo, message, quoteStart, quoteEnd);
  }

  public boolean isEmpty() {
    return equals(EMPTY);
  }

  /** The sparse map stored on a chunk: only the components that are set. */
  public Map<String, Object> toMetadata() {
    Map<String, Object> map = new LinkedHashMap<>();
    putIfSet(map, "pageFrom", pageFrom);
    putIfSet(map, "pageTo", pageTo);
    putIfSet(map, "path", path);
    putIfSet(map, "slide", slide);
    putIfSet(map, "sheet", sheet);
    putIfSet(map, "rowFrom", rowFrom);
    putIfSet(map, "rowTo", rowTo);
    putIfSet(map, "message", message);
    putIfSet(map, "quoteStart", quoteStart);
    putIfSet(map, "quoteEnd", quoteEnd);
    return map;
  }

  /**
   * The anchor a stored metadata value describes - the map {@link #toMetadata} wrote, read back
   * from JSON, where numbers may come back as any {@link Number}. {@link #EMPTY} for anything else.
   */
  public static ChunkAnchor fromMetadata(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return EMPTY;
    }
    return new ChunkAnchor(
        intOf(map.get("pageFrom")),
        intOf(map.get("pageTo")),
        stringsOf(map.get("path")),
        intOf(map.get("slide")),
        stringOf(map.get("sheet")),
        longOf(map.get("rowFrom")),
        longOf(map.get("rowTo")),
        intOf(map.get("message")),
        stringOf(map.get("quoteStart")),
        stringOf(map.get("quoteEnd")));
  }

  private static void putIfSet(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static Integer intOf(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static Long longOf(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private static String stringOf(Object value) {
    return value instanceof String text && !text.isEmpty() ? text : null;
  }

  private static List<String> stringsOf(Object value) {
    if (!(value instanceof List<?> list)) {
      return null;
    }
    List<String> strings = new ArrayList<>(list.size());
    for (Object entry : list) {
      if (entry instanceof String text) {
        strings.add(text);
      }
    }
    return strings;
  }
}
