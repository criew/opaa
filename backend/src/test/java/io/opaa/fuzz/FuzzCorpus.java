package io.opaa.fuzz;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The seeds of the parser fuzzing: the generated and found inputs under {@link #CORPUS} and the
 * format samples under {@link #TEST_DOCUMENTS}, each file whose extension the installation admits.
 */
final class FuzzCorpus {

  /** Seeds of {@link FuzzCorpusGenerator} and inputs a campaign found, relative to the repo. */
  static final String CORPUS = "backend/src/test/resources/fuzz/corpus";

  static final String TEST_DOCUMENTS = "backend/src/test/resources/test-documents";

  /** One seed: its file name, extension with dot, and content. */
  record Seed(String name, String extension, byte[] bytes) {

    @Override
    public String toString() {
      return name;
    }
  }

  private FuzzCorpus() {}

  /** The repository root the build hands every test JVM ({@code opaa.repositoryRoot}). */
  static Path repositoryRoot() {
    String root = System.getProperty("opaa.repositoryRoot");
    if (root == null) {
      throw new IllegalStateException("opaa.repositoryRoot is not set; run through Gradle");
    }
    return Path.of(root);
  }

  /** Every seed whose extension is among {@code admitted}, sorted by path. */
  static List<Seed> load(Collection<String> admitted) {
    Path root = repositoryRoot();
    return Stream.of(CORPUS, TEST_DOCUMENTS)
        .map(root::resolve)
        .flatMap(FuzzCorpus::files)
        .filter(file -> admitted.contains(extensionOf(file.getFileName().toString())))
        .sorted()
        .map(FuzzCorpus::seed)
        .toList();
  }

  static String extensionOf(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static Stream<Path> files(Path directory) {
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(Files::isRegularFile).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Seed seed(Path file) {
    try {
      String name = file.getFileName().toString();
      return new Seed(name, extensionOf(name), Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
