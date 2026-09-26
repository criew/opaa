package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.PackageDependencyScanner;
import io.opaa.permission.PackageDependencyScanner.Reference;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The holdings sit below the indexing, the indexing below the library administration: {@code
 * io.opaa.knowledge} names neither {@code io.opaa.indexing} nor {@code io.opaa.library}, and {@code
 * io.opaa.indexing} does not name {@code io.opaa.library}. What a lower package needs from an upper
 * one it declares as an interface the upper one implements ({@link FolderDocumentDeleter}).
 */
class KnowledgePackageBoundaryTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  private static final String KNOWLEDGE = "io.opaa.knowledge";
  private static final String INDEXING = "io.opaa.indexing";
  private static final String LIBRARY = "io.opaa.library";

  private static List<Reference> references;

  @BeforeAll
  static void scanOnce() {
    references = PackageDependencyScanner.scan(MAIN_SOURCES);
  }

  @Test
  void theHoldingsNameNeitherTheIndexingNorTheAdministration() {
    assertThat(edges(KNOWLEDGE, INDEXING))
        .as("io.opaa.knowledge sits below the indexing")
        .isEmpty();
    assertThat(edges(KNOWLEDGE, LIBRARY))
        .as("io.opaa.knowledge sits below the library administration")
        .isEmpty();
  }

  @Test
  void theIndexingDoesNotNameTheAdministration() {
    assertThat(edges(INDEXING, LIBRARY))
        .as("the indexing reaches the library through io.opaa.knowledge only")
        .isEmpty();
  }

  /** Guards the premise: the rules above would pass just as well against an empty tree. */
  @Test
  void theDirectionsDownwardAreActuallySeen() {
    assertThat(edges(INDEXING, KNOWLEDGE)).isNotEmpty();
    assertThat(edges(LIBRARY, KNOWLEDGE)).isNotEmpty();
    assertThat(edges(LIBRARY, INDEXING)).isNotEmpty();
  }

  private static List<Reference> edges(String from, String to) {
    return references.stream()
        .filter(reference -> isWithin(reference.fromPackage(), from))
        .filter(reference -> isWithin(reference.toPackage(), to))
        .toList();
  }

  private static boolean isWithin(String packageName, String root) {
    return packageName.equals(root) || packageName.startsWith(root + ".");
  }
}
