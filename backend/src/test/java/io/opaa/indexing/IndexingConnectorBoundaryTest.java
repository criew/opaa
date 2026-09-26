package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.PackageDependencyScanner;
import io.opaa.permission.PackageDependencyScanner.Reference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The indexing core knows no connector, and no connector knows another: each connector registers
 * itself in its own package and reaches the core only through the source SPI. Every direct
 * subpackage of {@code io.opaa.indexing.source} is a connector, so a new one is covered without
 * touching this test. No package outside {@code io.opaa.indexing} - the administration in {@code
 * io.opaa.library} and the API included - names a connector either.
 */
class IndexingConnectorBoundaryTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  private static final String SOURCE_PACKAGE = "io.opaa.indexing.source";

  @Test
  void connectorsAreDiscoveredFromTheSourcePackage() throws IOException {
    assertThat(connectors()).contains("s3", "confluence", "rss", "web", "filesystem");
  }

  @Test
  void noIndexingPackageReachesIntoAForeignConnector() throws IOException {
    List<String> connectors = connectors();
    List<Reference> offenses =
        PackageDependencyScanner.scan(MAIN_SOURCES).stream()
            .filter(reference -> reference.fromPackage().startsWith("io.opaa.indexing"))
            .filter(reference -> connectorOf(reference.toPackage(), connectors) != null)
            .filter(
                reference ->
                    !Objects.equals(
                        connectorOf(reference.fromPackage(), connectors),
                        connectorOf(reference.toPackage(), connectors)))
            .toList();

    assertThat(offenses)
        .as(
            "the indexing core must not name a connector, and connectors must not name each other"
                + " - register the connector in its own @Configuration and share code through the"
                + " core instead")
        .isEmpty();
  }

  @Test
  void noPackageOutsideTheIndexingReachesIntoAConnector() throws IOException {
    List<String> connectors = connectors();
    List<Reference> offenses =
        PackageDependencyScanner.scan(MAIN_SOURCES).stream()
            .filter(reference -> !reference.fromPackage().startsWith("io.opaa.indexing"))
            .filter(reference -> connectorOf(reference.toPackage(), connectors) != null)
            .toList();

    assertThat(offenses)
        .as(
            "the administration (library), the API and every other package reach a connector"
                + " only through the SourceConnectorRegistry and the capabilities it hands out")
        .isEmpty();
  }

  /** The names of the direct subpackages of {@code io.opaa.indexing.source}. */
  private static List<String> connectors() throws IOException {
    Path sourceDirectory = MAIN_SOURCES.resolve(SOURCE_PACKAGE.replace('.', '/'));
    try (Stream<Path> children = Files.list(sourceDirectory)) {
      return children
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    }
  }

  /** The connector {@code packageName} belongs to, or {@code null} for any other package. */
  private static String connectorOf(String packageName, List<String> connectors) {
    for (String connector : connectors) {
      String root = SOURCE_PACKAGE + "." + connector;
      if (packageName.equals(root) || packageName.startsWith(root + ".")) {
        return connector;
      }
    }
    return null;
  }
}
