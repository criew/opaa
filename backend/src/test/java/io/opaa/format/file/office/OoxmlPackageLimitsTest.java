package io.opaa.format.file.office;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.file.tabular.TabularDocumentFormat;
import io.opaa.format.file.tabular.TabularProperties;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A DOCX, PPTX or XLSX whose XML parts unpack to more than the configured limits fails to parse
 * before POI builds a DOM of them. The packages here are a few hundred KB of highly compressible
 * XML, measured against limits of the same scale, so the check is shown without filling the heap.
 */
class OoxmlPackageLimitsTest {

  private static final ParserLimitProperties SMALL =
      new ParserLimitProperties(0, 200_000, 300_000, 0, 0, 0);

  private static final String CONTENT_TYPES =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"rels\""
          + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
          + "<Default Extension=\"mp4\" ContentType=\"video/mp4\"/>"
          + "<Default Extension=\"ttf\" ContentType=\"application/x-font-ttf\"/>"
          + "<Override PartName=\"/word/document.xml\" ContentType=\"application/"
          + "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
          + "</Types>";

  private static final String ROOT_RELS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
          + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
          + "2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
          + "</Relationships>";

  @TempDir Path tempDir;

  @Test
  void aDocumentPartPastThePartLimitFails() throws IOException {
    Path file = docx("bomb.docx", Map.of("word/document.xml", documentXml(20_000)));

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void xmlPartsPastTheTotalLimitTogetherFail() throws IOException {
    Map<String, byte[]> parts = new LinkedHashMap<>();
    parts.put("word/document.xml", documentXml(3_000));
    parts.put("word/extra1.xml", documentXml(3_000));
    parts.put("word/extra2.xml", documentXml(3_000));
    Path file = docx("parts.docx", parts);

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aPartWithoutDeclaredContentTypeCountsAsXml() throws IOException {
    Map<String, byte[]> parts = new LinkedHashMap<>();
    parts.put("word/document.xml", documentXml(10));
    parts.put("word/hidden.bin", documentXml(20_000));
    Path file = docx("undeclared.docx", parts);

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aLargeImagePartDoesNotCountAgainstTheXmlLimits() throws IOException {
    byte[] image = new byte[1_000_000];
    new Random(7).nextBytes(image);
    byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    System.arraycopy(pngSignature, 0, image, 0, pngSignature.length);
    Map<String, byte[]> parts = new LinkedHashMap<>();
    parts.put("word/document.xml", documentXml(10));
    parts.put("word/media/image1.png", image);
    Path file = docx("bild.docx", parts);

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  /** A video ({@code 00 00 00 20 ftyp}) or font ({@code 00 01 00 00}) is no XML part. */
  @Test
  void largeMediaAndFontPartsStartingWithAZeroByteDoNotCount() throws IOException {
    Map<String, byte[]> parts = new LinkedHashMap<>();
    parts.put("word/document.xml", documentXml(10));
    parts.put(
        "word/media/media1.mp4",
        binary(new byte[] {0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}));
    parts.put("word/fonts/font1.ttf", binary(new byte[] {0x00, 0x01, 0x00, 0x00}));
    Path file = docx("video.docx", parts);

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  /** Without a byte order mark an XML parser still detects UTF-16BE from {@code 00 3C 00 3F}. */
  @Test
  void utf16BigEndianXmlWithoutByteOrderMarkCounts() throws IOException {
    String xml =
        new String(documentXml(20_000), StandardCharsets.UTF_8).replace("UTF-8", "UTF-16BE");
    Map<String, byte[]> parts = new LinkedHashMap<>();
    parts.put("word/document.xml", documentXml(10));
    parts.put("word/hidden.bin", xml.getBytes(StandardCharsets.UTF_16BE));
    Path file = docx("utf16.docx", parts);

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /**
   * POI picks the parser by relationship type, not by content type: here the main document is a
   * {@code .png} part declared as {@code image/png} and is still read as WordprocessingML.
   */
  @Test
  void anXmlPartDisguisedAsAnImageCountsAgainstThePartLimit() throws IOException {
    String contentTypes =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\""
            + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
            + "</Types>";
    Map<String, byte[]> parts = new LinkedHashMap<>();
    parts.put(
        "_rels/.rels",
        ROOT_RELS
            .replace("word/document.xml", "word/document.png")
            .getBytes(StandardCharsets.UTF_8));
    parts.put("word/document.png", documentXml(20_000));
    Path file = zip("getarnt.docx", contentTypes, parts);

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aSmallDocxWithinTheLimitsIsReadAsBefore() throws IOException {
    Path file = docx("klein.docx", Map.of("word/document.xml", documentXml(10)));

    assertThat(run(new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL), file))
        .isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  @Test
  void aWorksheetPastThePartLimitFails() throws IOException {
    Path file = tempDir.resolve("gross.xlsx");
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        OutputStream out = Files.newOutputStream(file)) {
      XSSFSheet sheet = workbook.createSheet("Daten");
      for (int r = 0; r < 5_000; r++) {
        sheet.createRow(r).createCell(0).setCellValue(r);
      }
      workbook.write(out);
    }
    var tabular =
        new TabularDocumentFormat(
            new TabularProperties(0, 0, 0, 0), HeadingSectionSplitterFixture.DEFAULT, SMALL);

    assertThat(run(tabular, file)).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aSlidePastThePartLimitFails() throws IOException {
    Path file = tempDir.resolve("gross.pptx");
    try (XMLSlideShow slideShow = new XMLSlideShow();
        OutputStream out = Files.newOutputStream(file)) {
      XSLFTextBox box = slideShow.createSlide().createTextBox();
      for (int i = 0; i < 5_000; i++) {
        box.addNewTextParagraph().addNewTextRun().setText("Absatz " + i);
      }
      slideShow.write(out);
    }
    var pptx = new PptxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT, SMALL);

    assertThat(run(pptx, file)).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  private static DocumentFormatResult.Outcome run(DocumentFormat format, Path file) {
    return DocumentFormatRunner.run(
            format, DocumentFormatSource.ofFile(file, file.getFileName().toString()))
        .outcome();
  }

  private Path docx(String name, Map<String, byte[]> parts) throws IOException {
    Map<String, byte[]> all = new LinkedHashMap<>();
    all.put("_rels/.rels", ROOT_RELS.getBytes(StandardCharsets.UTF_8));
    all.putAll(parts);
    return zip(name, CONTENT_TYPES, all);
  }

  private Path zip(String name, String contentTypes, Map<String, byte[]> parts) throws IOException {
    Path file = tempDir.resolve(name);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
      zip.setLevel(9);
      put(zip, "[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, byte[]> part : parts.entrySet()) {
        put(zip, part.getKey(), part.getValue());
      }
    }
    return file;
  }

  private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  /** One MB of random bytes behind {@code signature}. */
  private static byte[] binary(byte[] signature) {
    byte[] bytes = new byte[1_000_000];
    new Random(11).nextBytes(bytes);
    System.arraycopy(signature, 0, bytes, 0, signature.length);
    return bytes;
  }

  private static byte[] documentXml(int paragraphs) {
    StringBuilder xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://"
                + "schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
    for (int i = 0; i < paragraphs; i++) {
      xml.append("<w:p><w:r><w:t>Absatz ").append(i).append("</w:t></w:r></w:p>");
    }
    return xml.append("</w:body></w:document>").toString().getBytes(StandardCharsets.UTF_8);
  }
}
