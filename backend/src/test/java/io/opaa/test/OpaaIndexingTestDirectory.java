package io.opaa.test;

import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

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
 *
 * <p>Unlike a JUnit {@code @TempDir}, {@link Files#createTempDirectory} does not delete its
 * directory on its own; a shutdown hook removes the whole {@link #BASE_DIR} tree (including every
 * test document a class copied into it) when the JVM exits, so a test run does not leave a
 * permanent temp directory behind.
 */
public final class OpaaIndexingTestDirectory {

  public static final Path BASE_DIR = createBaseDir();

  private OpaaIndexingTestDirectory() {}

  private static Path createBaseDir() {
    try {
      Path dir = Files.createTempDirectory("opaa-indexing-it-");
      Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(dir)));
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  // Best-effort cleanup on JVM exit - a leftover file here does not affect any
                  // test outcome, only how much of the OS temp directory this run left behind.
                }
              });
    } catch (IOException e) {
      // Same best-effort reasoning as above: this only affects temp directory hygiene.
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
