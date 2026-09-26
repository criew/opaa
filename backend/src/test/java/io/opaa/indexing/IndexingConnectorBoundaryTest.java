package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.PackageDependencyScanner;
import io.opaa.permission.PackageDependencyScanner.Reference;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The indexing core knows no connector, and no connector knows another: each connector registers
 * itself in its own package and reaches the core only through the source SPI. Packages outside
 * {@code io.opaa.indexing} are not covered here.
 */
class IndexingConnectorBoundaryTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  private static final List<String> CONNECTORS =
      List.of("s3", "confluence", "rss", "web", "filesystem");

  @Test
  void noIndexingPackageReachesIntoAForeignConnector() {
    List<Reference> offenses =
        PackageDependencyScanner.scan(MAIN_SOURCES).stream()
            .filter(reference -> reference.fromPackage().startsWith("io.opaa.indexing"))
            .filter(reference -> connectorOf(reference.toPackage()) != null)
            .filter(
                reference ->
                    !Objects.equals(
                        connectorOf(reference.fromPackage()), connectorOf(reference.toPackage())))
            .toList();

    assertThat(offenses)
        .as(
            "the indexing core must not name a connector, and connectors must not name each other"
                + " - register the connector in its own @Configuration and share code through the"
                + " core instead")
        .isEmpty();
  }

  /** The connector {@code packageName} belongs to, or {@code null} for any other package. */
  private static String connectorOf(String packageName) {
    for (String connector : CONNECTORS) {
      String root = "io.opaa.indexing.source." + connector;
      if (packageName.equals(root) || packageName.startsWith(root + ".")) {
        return connector;
      }
    }
    return null;
  }
}
