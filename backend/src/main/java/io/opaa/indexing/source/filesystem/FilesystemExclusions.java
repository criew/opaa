package io.opaa.indexing.source.filesystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which entries below a FILESYSTEM library's {@code sourcePath} are not part of its source.
 * Always excluded are hidden entries (name starting with {@code .}) and the Windows system folders
 * {@code $RECYCLE.BIN} and {@code System Volume Information}; on top come the library's {@link
 * FilesystemSourceSettings#excludePatterns()}, matched as {@link FilesystemGlob} against the path
 * relative to {@code sourcePath}.
 */
public final class FilesystemExclusions {

  private static final Set<String> SYSTEM_FOLDERS =
      Set.of("$recycle.bin", "system volume information");

  private final List<FilesystemGlob> globs;

  private FilesystemExclusions(List<FilesystemGlob> globs) {
    this.globs = globs;
  }

  public static FilesystemExclusions of(FilesystemSourceSettings settings) {
    return new FilesystemExclusions(
        settings.excludePatterns().stream().map(FilesystemGlob::compile).toList());
  }

  /**
   * Whether the entry at {@code relative} - a non-empty path relative to {@code sourcePath} - is
   * excluded. Only the entry's own name is checked against the defaults: a walk never reaches the
   * content of an excluded directory.
   */
  public boolean excludes(Path relative, boolean directory) {
    Path name = relative.getFileName();
    if (name != null && isExcludedByDefault(name.toString())) {
      return true;
    }
    List<String> levels = new ArrayList<>();
    for (Path level : relative) {
      levels.add(level.toString());
    }
    return globs.stream().anyMatch(glob -> glob.matches(levels, directory));
  }

  static boolean isExcludedByDefault(String name) {
    return name.startsWith(".") || SYSTEM_FOLDERS.contains(name.toLowerCase(Locale.ROOT));
  }
}
