package io.opaa.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One process-wide base directory for every class carrying {@link OpaaIndexingIntegrationTest},
 * created exactly once so the {@code opaa.indexing.filesystem-allowlist} property {@link
 * OpaaIndexingFilesystemAllowlistInitializer} registers stays identical across classes (an
 * identical, static value keeps the Spring context cache key identical too - a fresh
 * {@code @TempDir} per class would not, see {@link OpaaIndexingIntegrationTest}'s Javadoc).
 *
 * <p>{@link FilesystemPathAllowlist#isAllowed} (see {@code io.opaa.indexing}) checks with {@code
 * Path#startsWith}, so a subdirectory of {@link #BASE_DIR} passes the allowlist check without
 * widening it beyond this base - each test class calls {@link #subdirectory(String)} for its own,
 * uniquely named subdirectory instead of sharing files directly under {@link #BASE_DIR}.
 */
public final class OpaaIndexingTestDirectory {

  public static final Path BASE_DIR = createBaseDir();

  private OpaaIndexingTestDirectory() {}

  private static Path createBaseDir() {
    try {
      return Files.createTempDirectory("opaa-indexing-it-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Creates (if absent) and returns the subdirectory {@code name} of {@link #BASE_DIR}. */
  public static Path subdirectory(String name) {
    Path dir = BASE_DIR.resolve(name);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return dir;
  }
}
