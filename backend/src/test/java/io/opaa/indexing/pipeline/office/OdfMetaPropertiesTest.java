package io.opaa.indexing.pipeline.office;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ODF side of {@code DocumentPipeline#readProperties} (ADR-0024): {@code meta.xml}'s title and
 * dates for ODT and ODP, the first level-1 heading for ODT - and the graceful outcomes when {@code
 * meta.xml} is missing, malformed or carries an unparseable date.
 */
class OdfMetaPropertiesTest {

  @TempDir Path tempDir;

  private final OdfProperties odfProperties = new OdfProperties(0, 0, 0, 0, 0);
  private final OdtDocumentPipeline odt = new OdtDocumentPipeline(odfProperties);
  private final OdpDocumentPipeline odp = new OdpDocumentPipeline(odfProperties);

  private static final String META =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
          xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
          xmlns:dc="http://purl.org/dc/elements/1.1/">
        <office:meta>
          <dc:title>Gebührensatzung der Stadt</dc:title>
          <meta:creation-date>2023-05-02T08:00:00</meta:creation-date>
          <dc:date>2024-11-05T16:45:12.123456</dc:date>
        </office:meta>
      </office:document-meta>
      """;

  private static final String ODT_CONTENT =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
          xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
        <office:body><office:text>
          <text:h text:outline-level="1">Gebührensatzung</text:h>
          <text:p>Diese Satzung regelt die Gebühren.</text:p>
        </office:text></office:body>
      </office:document-content>
      """;

  private static final String ODP_CONTENT =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
          xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
          xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
        <office:body><office:presentation>
          <draw:page draw:name="Folie 1"><draw:frame><draw:text-box>
            <text:p>Willkommen</text:p>
          </draw:text-box></draw:frame></draw:page>
        </office:presentation></office:body>
      </office:document-content>
      """;

  @Test
  void odtReadsTitleDatesAndFirstHeadingWithoutChunking() throws IOException {
    Path file = write(tempDir.resolve("satzung.odt"), ODT_CONTENT, META);

    DocumentProperties properties =
        odt.readProperties(DocumentPipelineSource.ofFile(file, "satzung.odt", ".odt"));

    assertThat(properties.title()).isEqualTo("Gebührensatzung der Stadt");
    assertThat(properties.createdAt()).isEqualTo(LocalDate.of(2023, 5, 2));
    assertThat(properties.modifiedAt()).isEqualTo(LocalDate.of(2024, 11, 5));
    assertThat(properties.firstHeading()).isEqualTo("Gebührensatzung");
    assertThat(properties.documentDate()).isNull();
    assertThat(properties.frontmatter()).isEmpty();
  }

  @Test
  void odtRunAttachesTheSameProperties() throws IOException {
    Path file = write(tempDir.resolve("satzung.odt"), ODT_CONTENT, META);

    DocumentProperties properties =
        odt.run(DocumentPipelineSource.ofFile(file, "satzung.odt", ".odt")).properties();

    assertThat(properties.title()).isEqualTo("Gebührensatzung der Stadt");
    assertThat(properties.firstHeading()).isEqualTo("Gebührensatzung");
  }

  @Test
  void odpReadsTitleAndDatesButNoHeading() throws IOException {
    Path file = write(tempDir.resolve("folien.odp"), ODP_CONTENT, META);

    DocumentProperties properties =
        odp.readProperties(DocumentPipelineSource.ofFile(file, "folien.odp", ".odp"));

    assertThat(properties.title()).isEqualTo("Gebührensatzung der Stadt");
    assertThat(properties.modifiedAt()).isEqualTo(LocalDate.of(2024, 11, 5));
    assertThat(properties.firstHeading()).isNull();
    assertThat(odp.run(DocumentPipelineSource.ofFile(file, "folien.odp", ".odp")).properties())
        .isEqualTo(properties);
  }

  @Test
  void aMissingMetaXmlStillYieldsTheHeading() throws IOException {
    Path file = write(tempDir.resolve("ohne-meta.odt"), ODT_CONTENT, null);

    DocumentProperties properties =
        odt.readProperties(DocumentPipelineSource.ofFile(file, "ohne-meta.odt", ".odt"));

    assertThat(properties.title()).isNull();
    assertThat(properties.modifiedAt()).isNull();
    assertThat(properties.firstHeading()).isEqualTo("Gebührensatzung");
  }

  @Test
  void aMalformedMetaXmlOrUnparseableDateNeverFailsTheDocument() throws IOException {
    Path malformed = write(tempDir.resolve("kaputt.odt"), ODT_CONTENT, "<office:document-meta>");
    assertThat(
            odt.readProperties(DocumentPipelineSource.ofFile(malformed, "kaputt.odt", ".odt"))
                .firstHeading())
        .isEqualTo("Gebührensatzung");

    Path oddDate =
        write(
            tempDir.resolve("datum.odt"),
            ODT_CONTENT,
            META.replace("2024-11-05T16:45:12.123456", "gestern")
                .replace("2023-05-02T08:00:00", "05.02"));
    DocumentProperties properties =
        odt.readProperties(DocumentPipelineSource.ofFile(oddDate, "datum.odt", ".odt"));
    assertThat(properties.title()).isEqualTo("Gebührensatzung der Stadt");
    assertThat(properties.createdAt()).isNull();
    assertThat(properties.modifiedAt()).isNull();
  }

  @Test
  void aSourceWithoutAFileHasNoProperties() {
    assertThat(odt.readProperties(DocumentPipelineSource.ofExtractedText("x", "x.odt")))
        .isEqualTo(DocumentProperties.EMPTY);
    assertThat(odp.readProperties(DocumentPipelineSource.ofExtractedText("x", "x.odp")))
        .isEqualTo(DocumentProperties.EMPTY);
  }

  private static Path write(Path file, String contentXml, String metaXml) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(contentXml.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
      if (metaXml != null) {
        out.putNextEntry(new ZipEntry("meta.xml"));
        out.write(metaXml.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return file;
  }
}
