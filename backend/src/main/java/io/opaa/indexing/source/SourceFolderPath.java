package io.opaa.indexing.source;

import io.opaa.library.LibraryFolderService;
import java.util.List;

/**
 * The folder chain a source item maps to, judged once for every connector that mirrors a directory
 * structure through {@link SourceFolderMirror}: a segment no {@code library_folders} row can carry
 * rejects the whole path (the item lands at the library's root), a chain a connector caps is cut at
 * {@link #MAX_DEPTH}. The connector derives the raw segments (a URL path, an S3 key, a file path);
 * this record only says whether they can become folders.
 *
 * @param segments the folder names outermost first; empty means the library's root
 * @param rejectedSegment the segment that made this path unusable, or {@code null}; a rejected path
 *     always has empty {@link #segments()} - half a path is worse than none
 * @param truncated whether the chain was cut at {@link #MAX_DEPTH}
 */
public record SourceFolderPath(List<String> segments, String rejectedSegment, boolean truncated) {

  public static final SourceFolderPath ROOT = new SourceFolderPath(List.of(), null, false);

  public static final int MAX_DEPTH = LibraryFolderService.MAX_DEPTH;

  /** The width of {@code library_folders.name}; a longer segment would fail the insert. */
  public static final int MAX_SEGMENT_LENGTH = 255;

  public SourceFolderPath {
    segments = List.copyOf(segments);
  }

  public boolean rejected() {
    return rejectedSegment != null;
  }

  /** Judges {@code segments} as given - a chain that may nest as deep as the source does. */
  public static SourceFolderPath of(List<String> segments) {
    return judge(segments, false);
  }

  /**
   * Cuts {@code segments} at {@link #MAX_DEPTH} first, so the item lies in the deepest allowed
   * folder; only the segments within the limit are judged.
   */
  public static SourceFolderPath capped(List<String> segments) {
    return judge(segments, true);
  }

  /** The path of an item whose segment {@code segment} cannot be a folder name. */
  public static SourceFolderPath rejecting(String segment) {
    return new SourceFolderPath(List.of(), segment, false);
  }

  /**
   * Whether {@code name} cannot be one folder row: blank, a traversal ({@code .}, {@code ..}), a
   * path separator of either kind, a NUL byte, or wider than the column.
   */
  public static boolean rejects(String name) {
    return name.isBlank()
        || isPathTraversalName(name)
        || name.indexOf('\0') >= 0
        || name.length() > MAX_SEGMENT_LENGTH;
  }

  /**
   * Whether {@code name} is a literal {@code .}/{@code ..} or carries a path separator - the class
   * of segment a crawler must not follow either, not only not mirror.
   */
  public static boolean isPathTraversalName(String name) {
    return name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\");
  }

  private static SourceFolderPath judge(List<String> segments, boolean cap) {
    boolean truncated = cap && segments.size() > MAX_DEPTH;
    List<String> chain = truncated ? segments.subList(0, MAX_DEPTH) : segments;
    for (String segment : chain) {
      if (rejects(segment)) {
        return rejecting(segment);
      }
    }
    return chain.isEmpty() ? ROOT : new SourceFolderPath(chain, null, truncated);
  }
}
