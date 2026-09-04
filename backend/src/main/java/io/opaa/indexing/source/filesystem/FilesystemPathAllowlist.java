package io.opaa.indexing.source.filesystem;

import java.nio.file.Path;
import java.util.List;

/**
 * Enforces the {@code FILESYSTEM} quellentyp's path allowlist (ADR-0018 Entscheidung 6): every
 * anlage-berechtigte caller may still choose {@code FILESYSTEM} as a quellentyp - no new role gates
 * that choice - but the {@code sourcePath} it configures must resolve underneath one of the
 * operator-configured base directories in {@link FilesystemProperties#allowlist()}. An empty
 * allowlist disables the FILESYSTEM quellentyp entirely - the safe default - rather than falling
 * back to "everything allowed".
 *
 * <p>Checked twice, deliberately: {@code io.opaa.library.KnowledgeLibraryService} enforces this at
 * creation and update time (a fast 400 for an operator who has not opened the directory), and
 * {@link AsyncIndexingExecutor} enforces it again at run time - the allowlist itself can be
 * narrowed after a library was created, so a run against a path that has since fallen outside the
 * allowlist must not silently succeed just because it once passed validation.
 */
public class FilesystemPathAllowlist {

  private final List<Path> baseDirectories;

  public FilesystemPathAllowlist(FilesystemProperties properties) {
    this.baseDirectories =
        properties.allowlist().stream().map(base -> Path.of(base).normalize()).toList();
  }

  /** Whether the operator has configured at least one base directory. */
  public boolean isConfigured() {
    return !baseDirectories.isEmpty();
  }

  /**
   * Whether {@code sourcePath}, after normalisation (so a {@code ../} traversal segment cannot
   * lexically escape a base directory), resolves underneath one of the configured base directories.
   * Symlinks are deliberately not resolved here - the allowlist is a lexical boundary on the
   * configured path, not a guarantee about what a symlink inside an allowed directory might point
   * at. Always {@code false} for a {@code null} path or an empty allowlist.
   */
  public boolean isAllowed(String sourcePath) {
    if (sourcePath == null || baseDirectories.isEmpty()) {
      return false;
    }
    Path normalized = Path.of(sourcePath).normalize();
    return baseDirectories.stream().anyMatch(normalized::startsWith);
  }
}
