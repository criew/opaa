package io.opaa.indexing.source.s3;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * The include and exclude globs of a library applied to an object key (ADR-0027, Entscheidung 1):
 * with include patterns a key must match at least one, and a key matching any exclude pattern is
 * out. A key outside the patterns is not part of the library's bestand - it is neither listed as
 * present nor counted, so narrowing the patterns removes what they no longer admit, like narrowing
 * a scope does. The globs are {@link java.nio.file.FileSystem#getPathMatcher} syntax, validated by
 * {@link S3SourceSettings}; {@code *} stays within one key segment, {@code **} crosses them.
 */
public final class S3KeyPatterns {

  private final List<PathMatcher> include;
  private final List<PathMatcher> exclude;

  private S3KeyPatterns(List<PathMatcher> include, List<PathMatcher> exclude) {
    this.include = include;
    this.exclude = exclude;
  }

  public static S3KeyPatterns of(S3SourceSettings settings) {
    return new S3KeyPatterns(
        compile(settings.includePatterns()), compile(settings.excludePatterns()));
  }

  private static List<PathMatcher> compile(List<String> patterns) {
    return patterns.stream()
        .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
        .toList();
  }

  public boolean admits(String key) {
    if (include.isEmpty() && exclude.isEmpty()) {
      return true;
    }
    Path path;
    try {
      path = Path.of(key);
    } catch (InvalidPathException e) {
      return include.isEmpty();
    }
    if (!include.isEmpty() && include.stream().noneMatch(matcher -> matcher.matches(path))) {
      return false;
    }
    return exclude.stream().noneMatch(matcher -> matcher.matches(path));
  }
}
