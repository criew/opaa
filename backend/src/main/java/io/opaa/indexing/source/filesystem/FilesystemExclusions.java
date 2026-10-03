package io.opaa.indexing.source.filesystem;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which entries below a FILESYSTEM library's {@code sourcePath} are not part of its source.
 * Always excluded are hidden entries (name starting with {@code .}) and the Windows system folders
 * {@code $RECYCLE.BIN} and {@code System Volume Information}; on top come the library's {@link
 * FilesystemSourceSettings#excludePatterns()}, globs matched against the path relative to {@code
 * sourcePath} with {@code /} as separator. A leading {@code **}{@code /} also matches no directory
 * at all, and a pattern {@code X/**} also matches {@code X} itself, so an excluded directory is
 * never entered.
 */
public final class FilesystemExclusions {

  private static final Set<String> SYSTEM_FOLDERS =
      Set.of("$recycle.bin", "system volume information");

  private final List<PathMatcher> matchers;

  private FilesystemExclusions(List<PathMatcher> matchers) {
    this.matchers = matchers;
  }

  public static FilesystemExclusions of(FilesystemSourceSettings settings) {
    List<PathMatcher> matchers = new ArrayList<>();
    for (String pattern : settings.excludePatterns()) {
      List<String> variants = new ArrayList<>(List.of(pattern));
      if (pattern.startsWith("**/") && pattern.length() > 3) {
        variants.add(pattern.substring(3));
      }
      for (String variant : List.copyOf(variants)) {
        if (variant.endsWith("/**") && variant.length() > 3) {
          variants.add(variant.substring(0, variant.length() - 3));
        }
      }
      for (String variant : variants) {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + variant));
      }
    }
    return new FilesystemExclusions(List.copyOf(matchers));
  }

  /**
   * Whether the entry at {@code relative} - a non-empty path relative to {@code sourcePath} - is
   * excluded. Only the entry's own name is checked against the defaults: a walk never reaches the
   * content of an excluded directory.
   */
  public boolean excludes(Path relative) {
    Path name = relative.getFileName();
    if (name != null && isExcludedByDefault(name.toString())) {
      return true;
    }
    return matchers.stream().anyMatch(matcher -> matcher.matches(relative));
  }

  static boolean isExcludedByDefault(String name) {
    return name.startsWith(".") || SYSTEM_FOLDERS.contains(name.toLowerCase(Locale.ROOT));
  }
}
