package io.opaa.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates {@code eval/} relative to the repository root, independent of the working directory
 * Gradle happens to run the test from (usually {@code backend/}, but not guaranteed).
 */
public final class RepoPaths {

  private static final String MARKER = "eval/corpus/comic-characters/MANIFEST.sha256";

  private RepoPaths() {}

  public static Path evalDir() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
      if (Files.exists(dir.resolve(MARKER))) {
        return dir.resolve("eval");
      }
    }
    throw new UncheckedIOException(
        new IOException(
            "Could not locate eval/ (expected to find '"
                + MARKER
                + "' by walking up from "
                + System.getProperty("user.dir")
                + "). Run this task from within the repository."));
  }
}
