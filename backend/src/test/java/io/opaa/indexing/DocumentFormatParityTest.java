package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.source.attachment.AttachmentCandidate;
import io.opaa.indexing.source.attachment.AttachmentProfile;
import io.opaa.indexing.source.web.UrlIndexingExecutor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * All three file-based indexing paths - filesystem ({@link DocumentService}), web directory ({@link
 * UrlIndexingExecutor}) and RSS attachments - must decide acceptance identically for the same
 * bytes, because all three go through {@link SupportedDocumentFormats#decideForFileName}.
 *
 * <p>Both sides are exercised through the production calls themselves ({@link
 * DocumentService#discoverFiles} and {@link UrlIndexingExecutor#decideForEntry}), never through a
 * reimplementation, so this test cannot silently drift from production.
 *
 * <p>{@code .doc} is not covered: POI can build every other strict type from scratch, but only ever
 * opens an existing legacy {@code .doc}. {@link SupportedDocumentFormatsTest} covers {@code
 * application/msword} against the media type string instead.
 */
class DocumentFormatParityTest {

  @TempDir Path tempDir;

  private static final String PDF_MAGIC_BYTES = "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection";

  /** The filesystem path's own verdict, taken from the call {@code AsyncIndexingExecutor} makes. */
  private static boolean acceptedFromFilesystem(Path file) throws IOException {
    return new DocumentService().discoverFiles(file.getParent()).supported().contains(file);
  }

  private static SupportedDocumentFormats.ContentDecision networkPathDecision(
      Path file, String entryName) throws IOException, InterruptedException {
    byte[] fullContent = Files.readAllBytes(file);
    byte[] prefix =
        fullContent.length <= SupportedDocumentFormats.DETECTION_PREFIX_BYTES
            ? fullContent
            : Arrays.copyOf(fullContent, SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
    // The full download the executor performs when the prefix alone stays inconclusive - stands in
    // for BoundedDownloader#download, whose result is likewise the complete file on disk.
    return UrlIndexingExecutor.decideForEntry(prefix, entryName, () -> file);
  }

  @ParameterizedTest
  @ValueSource(strings = {"handbuch.md", "notiz.txt", "scan.png", "archiv.zip"})
  void bothIndexingPathsDecideAlikeForTheSameContent(String fileName) throws Exception {
    // Ambiguous text content is only accepted under .md/.txt, whatever the name suggests - see
    // SupportedDocumentFormats#decideForFileName.
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, "Ganz gewöhnlicher, lesbarer Text.", StandardCharsets.UTF_8);

    boolean filesystemAccepted = acceptedFromFilesystem(file);
    boolean networkAccepted = networkPathDecision(file, fileName).supported();

    assertThat(networkAccepted)
        .as(
            "'%s' must be treated identically by both indexing paths; filesystem says %s, "
                + "network says %s",
            fileName, filesystemAccepted, networkAccepted)
        .isEqualTo(filesystemAccepted);
  }

  @ParameterizedTest
  @ValueSource(strings = {"bescheid.pdf", "DATEI-IN-GROSSBUCHSTABEN.PDF"})
  void bothIndexingPathsAcceptAGenuinePdfUnderItsOwnExtension(String fileName) throws Exception {
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, fileName);
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptAGenuineDocxUnderItsOwnExtension() throws Exception {
    // A genuine .docx under its own matching extension - the baseline the mismatch and rejection
    // cases below are contrasted against.
    Path file = tempDir.resolve("vermerk.docx");
    Files.write(file, realDocxBytes());

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, "vermerk.docx");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptAGenuinePptxUnderItsOwnExtension() throws Exception {
    Path file = tempDir.resolve("folien.pptx");
    Files.write(file, realPptxBytes());

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, "folien.pptx");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptAGenuineHtmlPageUnderItsOwnExtension() throws Exception {
    Path file = tempDir.resolve("seite.html");
    Files.writeString(
        file,
        "<html><body><main><h1>Titel</h1><p>Inhalt.</p></main></body></html>",
        StandardCharsets.UTF_8);

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, "seite.html");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptAGenuineXlsxUnderItsOwnExtension() throws Exception {
    Path file = tempDir.resolve("gebuehren.xlsx");
    Files.write(file, realXlsxBytes());

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, "gebuehren.xlsx");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  // ODF: the network path's bounded prefix detection
  // (SupportedDocumentFormats#DETECTION_PREFIX_BYTES) must resolve each ODF media type from a real
  // file too, not only from a media-type string.

  @ParameterizedTest
  @ValueSource(strings = {"odt", "ods", "odp"})
  void bothIndexingPathsAcceptAGenuineOdfFileUnderItsOwnExtension(String extension)
      throws Exception {
    String fileName = "dokument." + extension;
    Path file = tempDir.resolve(fileName);
    Files.write(file, realOdfBytes(extension));

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, fileName);
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptReadableContentDespiteAWrongExtensionAndReportTheSameMismatch()
      throws Exception {
    // A real PDF mislabeled .csv is accepted on both paths, and both report the same detected
    // extension - content decides, the claimed extension is only reported as a mismatch.
    Path file = tempDir.resolve("bescheid.csv");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(acceptedFromFilesystem(file)).isTrue();

    var networkDecision = networkPathDecision(file, "bescheid.csv");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isTrue();
    assertThat(networkDecision.detectedExtension()).isEqualTo(".pdf");
  }

  @Test
  void bothIndexingPathsRejectUnsupportedContentEvenWithASupportedLookingExtension()
      throws Exception {
    Path file = tempDir.resolve("image.pdf");
    Files.write(file, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

    assertThat(acceptedFromFilesystem(file)).isFalse();
    assertThat(networkPathDecision(file, "image.pdf").supported()).isFalse();
  }

  // EML/MSG against real content: the .msg detection depends on the optional
  // tika-parser-microsoft-module, so a dependency trim must fail here rather than silently.

  @Test
  void bothIndexingPathsAcceptAGenuineEmlUnderItsOwnExtension() throws Exception {
    Message message =
        Message.Builder.of()
            .setSubject("Anfrage Bauantrag")
            .setFrom("max@example.org")
            .setTo("erika@example.org")
            .setBody("Bitte pruefen Sie den Antrag.", StandardCharsets.UTF_8)
            .build();
    Path file = tempDir.resolve("vorgang.eml");
    Files.write(file, DefaultMessageWriter.asBytes(message));

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, "vorgang.eml");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptAGenuineMsgUnderItsOwnExtension() throws Exception {
    Path file = tempDir.resolve("vorgang.msg");
    try (InputStream in =
        DocumentFormatParityTest.class
            .getClassLoader()
            .getResourceAsStream("test-documents/mail/simple_test_msg.msg")) {
      assertThat(in).as("Test resource must exist").isNotNull();
      Files.copy(in, file);
    }

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, "vorgang.msg");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
    assertThat(networkDecision.detectedExtension())
        .as(
            "the .msg detection depends on the optional tika-parser-microsoft-module actually"
                + " being on the classpath - if this ever silently drops, this assertion catches"
                + " it instead of every real MSG upload quietly starting to fail")
        .isEqualTo(".msg");
  }

  @Test
  void bothIndexingPathsAcceptAGenuineMsgLargerThanTheDetectionPrefix() throws Exception {
    // Regression guard for #1229: an OLE2 file's directory sector may sit past the network path's
    // bounded prefix, where the same bytes detect only as the generic application/x-tika-msoffice.
    // The prefix decision must then fall back to the complete file instead of rejecting.
    Path file = tempDir.resolve("outlook-mail-mit-pdf-anhang.msg");
    try (InputStream in =
        DocumentFormatParityTest.class
            .getClassLoader()
            .getResourceAsStream("test-documents/mail/attachment_msg_pdf.msg")) {
      assertThat(in).as("Test resource must exist").isNotNull();
      Files.copy(in, file);
    }
    assertThat(Files.size(file))
        .as("the fixture must exceed the detection prefix for this guard to mean anything")
        .isGreaterThan(SupportedDocumentFormats.DETECTION_PREFIX_BYTES);

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision = networkPathDecision(file, "outlook-mail-mit-pdf-anhang.msg");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
    assertThat(networkDecision.detectedExtension()).isEqualTo(".msg");
  }

  @Test
  void aDocxLargerThanTheDetectionPrefixIsAlreadyResolvedFromThePrefixAlone() throws Exception {
    // A written OOXML archive carries [Content_Types].xml as its first entry, so Tika resolves the
    // specific media type from the prefix regardless of file size - DOCX/ODF never depend on
    // SupportedDocumentFormats#decideForPrefix's full-download fallback.
    Path file = tempDir.resolve("umfangreicher-bericht.docx");
    Files.write(file, largeIncompressibleDocxBytes());
    assertThat(Files.size(file)).isGreaterThan(SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
    byte[] prefix =
        Arrays.copyOf(Files.readAllBytes(file), SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
    assertThat(SupportedDocumentFormats.detectMediaType(prefix))
        .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    assertThat(acceptedFromFilesystem(file)).isTrue();
    var networkDecision =
        UrlIndexingExecutor.decideForEntry(
            prefix,
            "umfangreicher-bericht.docx",
            () -> {
              throw new AssertionError("a resolved OOXML prefix must not trigger a full download");
            });
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  // --- The RSS attachment path decides alike too ---------------------------------------------

  @Test
  void theRssAttachmentPathAcceptsTheSameMislabeledPdfTheOtherTwoPathsDo() throws Exception {
    // AttachmentProfile.GENERIC must let a link through whose extension is unknown, and
    // RssFeedIndexingExecutor#processAttachment must then decide on the downloaded bytes exactly
    // as DocumentService and UrlIndexingExecutor do.
    Element content =
        Jsoup.parse(
                "<main><a href=\"https://example.gov/downloads/bescheid.csv\">Bescheid</a></main>",
                "https://example.gov/artikel/mein-artikel")
            .body();
    List<AttachmentCandidate> candidates =
        AttachmentProfile.GENERIC.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));
    assertThat(candidates)
        .as(
            "a link with an extension SupportedDocumentFormats does not recognize must still "
                + "become a candidate - only its downloaded content decides from here")
        .hasSize(1);

    AttachmentCandidate candidate = candidates.getFirst();
    Path file = tempDir.resolve("downloaded-attachment");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    var rssDecision =
        SupportedDocumentFormats.decideForFileName(
            candidate.suggestedFileName(), SupportedDocumentFormats.detectMediaType(file));
    var filesystemDecision = acceptedFromFilesystem(file);
    var networkDecision = networkPathDecision(file, "bescheid.csv");

    assertThat(rssDecision.supported()).isTrue();
    assertThat(rssDecision.extensionMismatch()).isTrue();
    assertThat(rssDecision.detectedExtension()).isEqualTo(".pdf");
    assertThat(filesystemDecision).isEqualTo(rssDecision.supported());
    assertThat(networkDecision.supported()).isEqualTo(rssDecision.supported());
    assertThat(networkDecision.extensionMismatch()).isEqualTo(rssDecision.extensionMismatch());
  }

  /** A DOCX whose body is random enough not to compress below the detection prefix. */
  private static byte[] largeIncompressibleDocxBytes() throws IOException {
    try (XWPFDocument document = new XWPFDocument()) {
      for (int i = 0; i < 4_000; i++) {
        document.createParagraph().createRun().setText(UUID.randomUUID().toString());
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] realDocxBytes() throws IOException {
    try (XWPFDocument document = new XWPFDocument()) {
      XWPFParagraph paragraph = document.createParagraph();
      XWPFRun run = paragraph.createRun();
      run.setText("Ein echter DOCX-Inhalt fuer den Formaterkennungstest.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] realOdfBytes(String extension) throws IOException {
    String resourceName = "test-documents/test-document." + extension;
    try (InputStream in =
        DocumentFormatParityTest.class.getClassLoader().getResourceAsStream(resourceName)) {
      assertThat(in).as("Test resource %s must exist", resourceName).isNotNull();
      return in.readAllBytes();
    }
  }

  private static byte[] realPptxBytes() throws IOException {
    try (XMLSlideShow slideShow = new XMLSlideShow()) {
      XSLFSlide slide = slideShow.createSlide();
      XSLFTextBox textBox = slide.createTextBox();
      textBox.setText("Ein echter PPTX-Inhalt fuer den Formaterkennungstest.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      slideShow.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] realXlsxBytes() throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Gebühren");
      Row row = sheet.createRow(0);
      row.createCell(0).setCellValue("Ein echter XLSX-Inhalt fuer den Formaterkennungstest.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }
}
