package io.opaa.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.test.ProductionDocumentFormats;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.xml.DcXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

/**
 * Tika's standard parser package without the libraries {@code build.gradle.kts} excludes from it
 * (junrar, jmatio, jai-imageio-core, rome with jdom2): they only serve formats OPAA does not admit
 * (RAR, MATLAB, TIFF/JPEG 2000 images, RSS/Atom feeds). Every admitted format must still be
 * detected as itself and yield text through Tika's own parsing. The fixtures are the demo corpus
 * folder that holds one document per admitted format ({@code
 * docs/handbuch/arbeitsmittel/wissensbibliotheken/formate/README.md}).
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

  /**
   * A parser whose library is missing must be dropped by Tika's service loading, not fail at parse
   * time: a {@link LinkageError} would pass the {@code RuntimeException} mapping of {@link
   * DocumentFormatRunner}. The bytes of each excluded format therefore reach no parser of it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("excludedFormatSamples")
  void contentOfAnExcludedFormatReachesNoParserOfIt(String name, byte[] content, String expected)
      throws Exception {
    Metadata metadata = new Metadata();

    new AutoDetectParser()
        .parse(
            new ByteArrayInputStream(content),
            new BodyContentHandler(-1),
            metadata,
            new ParseContext());

    assertThat(metadata.getValues(TikaCoreProperties.TIKA_PARSED_BY))
        .as("parsers that saw the %s sample", name)
        .contains(expected)
        .noneMatch(parser -> EXCLUDED_PARSERS.stream().anyMatch(parser::endsWith));
  }

  private static final List<String> EXCLUDED_PARSERS =
      List.of("RarParser", "MatParser", "FeedParser");

  static Stream<Arguments> excludedFormatSamples() {
    byte[] rar = {'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00, 0, 0, 0, 0};
    byte[] mat =
        String.format("%-116s", "MATLAB 5.0 MAT-file, Platform: GLNXA64")
            .concat("\0\0\0\0\0\0\0\0\u0000\u0001IM")
            .getBytes(StandardCharsets.ISO_8859_1);
    byte[] rss =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Meldungen</title>
        <item><title>Eintrag</title><link>https://example.org/1</link></item>
        </channel></rss>
        """
            .getBytes(StandardCharsets.UTF_8);
    byte[] atom =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"><title>Meldungen</title>
        <entry><title>Eintrag</title><id>urn:x:1</id></entry></feed>
        """
            .getBytes(StandardCharsets.UTF_8);
    String empty = EmptyParser.class.getName();
    String xml = DcXMLParser.class.getName();
    return Stream.of(
        Arguments.of("RAR", rar, empty),
        Arguments.of("MAT", mat, empty),
        Arguments.of("RSS", rss, xml),
        Arguments.of("Atom", atom, xml));
  }

  @Test
  void fixturesCoverEveryAdmittedExtension() throws IOException {
    List<String> fixtureExtensions =
        fixtures().map(TikaWithoutExcludedLibrariesTest::extension).sorted().toList();

    assertThat(fixtureExtensions)
        .containsExactlyInAnyOrderElementsOf(supportedFormats.extensions());
  }

  @ParameterizedTest
  @MethodSource("fixtures")
  void tikaDetectsAndExtractsEveryAdmittedFormat(Path file) throws IOException {
    String extension = extension(file);

    String detected = SupportedDocumentFormats.detectMediaType(file);
    assertThat(supportedFormats.decideForFileName(file.getFileName().toString(), detected))
        .as("detected %s", detected)
        .isEqualTo(new SupportedDocumentFormats.ContentDecision(true, extension, false));

    // Tika itself, embedded parts included: a mail's body is one of them, and only the fallback
    // format, which skips them, goes through DocumentService
    BodyContentHandler text = new BodyContentHandler(-1);
    try (InputStream in = Files.newInputStream(file)) {
      new AutoDetectParser().parse(in, text, new Metadata(), new ParseContext());
    } catch (SAXException | TikaException e) {
      throw new IOException(e);
    }
    assertThat(text.toString()).as("text extracted from %s", file).isNotBlank();
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
