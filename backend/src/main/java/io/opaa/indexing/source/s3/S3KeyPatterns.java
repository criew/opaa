package io.opaa.indexing.source.s3;

import io.opaa.indexing.source.SourceGlob;
import java.util.List;

/**
 * The include and exclude globs of a library applied to an object key (ADR-0027, Entscheidung 1):
 * with include patterns a key must match at least one, and a key matching any exclude pattern is
 * out. A key outside the patterns is not part of the library's bestand - it is neither listed as
 * present nor counted, so narrowing the patterns removes what they no longer admit, like narrowing
 * a scope does. The globs are {@link SourceGlob}s on the key split at every {@code /}, validated by
 * {@link S3SourceSettings}; {@code *} stays within one level, a level {@code **} spans any number.
 */
public final class S3KeyPatterns {

  private final List<SourceGlob> include;
  private final List<SourceGlob> exclude;

  private S3KeyPatterns(List<SourceGlob> include, List<SourceGlob> exclude) {
    this.include = include;
    this.exclude = exclude;
  }

  public static S3KeyPatterns of(S3SourceSettings settings) {
    return new S3KeyPatterns(
        compile(settings.includePatterns()), compile(settings.excludePatterns()));
  }

  private static List<SourceGlob> compile(List<String> patterns) {
    return patterns.stream().map(SourceGlob::compile).toList();
  }

  public boolean admits(String key) {
    if (include.isEmpty() && exclude.isEmpty()) {
      return true;
    }
    List<String> levels = List.of(key.split("/", -1));
    if (!include.isEmpty() && include.stream().noneMatch(glob -> glob.matches(levels, false))) {
      return false;
    }
    return exclude.stream().noneMatch(glob -> glob.matches(levels, false));
  }
}
