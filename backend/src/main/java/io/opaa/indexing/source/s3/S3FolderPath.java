package io.opaa.indexing.source.s3;

import io.opaa.library.LibraryFolderService;
import java.util.ArrayList;
import java.util.List;

/**
 * The folder chain an S3 object maps to (ADR-0027, Entscheidung 5): the key's folder segments below
 * its scope's prefix, and - when the library has more than one scope - a segment chain of the
 * bucket name and the prefix segments above them, never a composite {@code bucket/prefix} name (a
 * folder name is slash-free and at most 255 characters). With a single scope its prefix is the
 * library's root.
 *
 * @param segments the folder names outermost first; empty means the library's root
 * @param rejectedSegment the segment that made this path unusable - one that reads as a traversal
 *     ({@code .}, {@code ..}), carries a backslash or exceeds the column width - or {@code null}; a
 *     rejected path always yields empty {@link #segments()}, so the object lands at the root
 * @param truncated whether the chain was cut at {@link #MAX_DEPTH} - an S3 key nests freely, a
 *     folder tree does not, so the object lies in the deepest allowed folder
 */
record S3FolderPath(List<String> segments, String rejectedSegment, boolean truncated) {

  static final int MAX_DEPTH = LibraryFolderService.MAX_DEPTH;

  private static final int MAX_SEGMENT_LENGTH = 255;

  boolean rejected() {
    return rejectedSegment != null;
  }

  /**
   * @param scopeRootChain whether the bucket and the prefix segments open the chain - {@code true}
   *     for a library with more than one scope
   */
  static S3FolderPath of(S3Scope scope, String key, boolean scopeRootChain) {
    List<String> raw = new ArrayList<>();
    if (scopeRootChain) {
      raw.add(scope.bucket());
      raw.addAll(split(scope.prefix()));
    }
    String relative = key.startsWith(scope.prefix()) ? key.substring(scope.prefix().length()) : key;
    int lastSlash = relative.lastIndexOf('/');
    if (lastSlash > 0) {
      raw.addAll(split(relative.substring(0, lastSlash)));
    }
    for (String segment : raw) {
      if (segment.equals(".")
          || segment.equals("..")
          || segment.indexOf('\\') >= 0
          || segment.length() > MAX_SEGMENT_LENGTH) {
        return new S3FolderPath(List.of(), segment, false);
      }
    }
    if (raw.size() > MAX_DEPTH) {
      return new S3FolderPath(List.copyOf(raw.subList(0, MAX_DEPTH)), null, true);
    }
    return new S3FolderPath(List.copyOf(raw), null, false);
  }

  /** The non-empty segments of a slash-separated path; a doubled slash carries no folder. */
  private static List<String> split(String path) {
    List<String> segments = new ArrayList<>();
    for (String segment : path.split("/")) {
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
    }
    return segments;
  }
}
