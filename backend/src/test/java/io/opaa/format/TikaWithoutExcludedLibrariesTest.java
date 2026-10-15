package io.opaa.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.test.ProductionDocumentFormats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tika's standard parser package without the libraries {@code build.gradle.kts} excludes from it
 * (junrar, jmatio, jai-imageio-core, rome with jdom2): they only serve formats OPAA does not admit
 * (RAR, MATLAB, TIFF/JPEG 2000 images, RSS/Atom feeds). Every admitted format must still be
 * detected as itself and yield text through Tika's own parsing. The fixtures are the demo corpus
 * folder that holds one document per admitted format ({@code docs/handbuch/formate/README.md}).
 */
class TikaWithoutExcludedLibrariesTest {

  private static final Path FORMAT_FIXTURES = Path.of("..", "demo", "corpus", "formate");

  private final SupportedDocumentFormats supportedFormats =
      ProductionDocumentFormats.supportedFormats();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.github.junrar.Junrar",
        "com.jmatio.io.MatFileReader",
        "com.github.jaiimageio.impl.plugins.tiff.TIFFImageReader",
        "com.rometools.rome.io.SyndFeedInput",
        "org.jdom2.Document"
      })
  void excludedLibraryIsNotOnTheClasspath(String className) {
    assertThatThrownBy(() -> Class.forName(className, false, getClass().getClassLoader()))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  void fixturesCoverEveryAdmittedExtension() throws IOException {
    List<String> fixtureExtensions = fixtures().map(TikaWithoutExcludedLibrariesTest::extension)
        .sorted()
        .toList();

    assertThat(fixtureExtensions).containsExactlyInAnyOrderElementsOf(supportedFormats.extensions());
  }

  @ParameterizedTest
  @MethodSource("fixtures")
  void tikaDetectsAndExtractsEveryAdmittedFormat(Path file) throws IOException {
    String extension = extension(file);

    String detected = SupportedDocumentFormats.detectMediaType(file);
    assertThat(supportedFormats.decideForFileName(file.getFileName().toString(), detected))
        .as("detected %s", detected)
        .isEqualTo(new SupportedDocumentFormats.ContentDecision(true, extension, false));

    var parsed = new DocumentService().parseDocument(file);
    assertThat(parsed).isNotEmpty();
    assertThat(parsed.getFirst().getText()).as("text extracted from %s", file).isNotBlank();
  }

  static Stream<Path> fixtures() throws IOException {
    try (Stream<Path> files = Files.list(FORMAT_FIXTURES)) {
      return files.filter(Files::isRegularFile).sorted().toList().stream();
    }
  }

  private static String extension(Path file) {
    String name = file.getFileName().toString();
    return name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
  }
}
