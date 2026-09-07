package io.opaa.indexing.source.s3;

import io.opaa.indexing.source.SourceFolderPath;
import java.util.ArrayList;
import java.util.List;

/**
 * The folder chain an S3 object maps to (ADR-0027, Entscheidung 5): the key's folder segments below
 * its scope's prefix, and - when the library has more than one scope - a segment chain of the
 * bucket name and the prefix segments above them, never a composite {@code bucket/prefix} name (a
 * folder name is slash-free). With a single scope its prefix is the library's root. An S3 key nests
 * freely, a folder tree does not, so the chain is {@link SourceFolderPath#capped capped}.
 */
final class S3FolderPath {

  private S3FolderPath() {}

  /**
   * @param scopeRootChain whether the bucket and the prefix segments open the chain - {@code true}
   *     for a library with more than one scope
   */
  static SourceFolderPath of(S3Scope scope, String key, boolean scopeRootChain) {
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
    return SourceFolderPath.capped(raw);
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
