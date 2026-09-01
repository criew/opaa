package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
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
 * Issue #375: the filesystem path ({@link DocumentService}) and the network path ({@link
 * UrlIndexingExecutor}) used to carry their own extension lists, so the same document was accepted
 * or rejected depending on how it entered the system. Issue #404 replaces the extension-based
 * decision itself with a content-based one on all three file-based paths (filesystem, web
 * directory, RSS attachments), made through the very same {@link
 * SupportedDocumentFormats#decideForFileName} - so none of them can drift apart on what "supported"
 * means, by construction rather than by lists someone has to remember to keep in sync.
 *
 * <p><b>{@code .doc} is not covered here (#404 review, finding 7 follow-up).</b> Every other strict
 * type below is generated as a genuine file via POI (already on the test classpath, see {@code
 * io.opaa.library.LibraryDocumentServiceTest#realDocxFile}'s identical reasoning) - legacy binary
 * {@code .doc} has no equivalent "build one from scratch" POI API ({@link
 * org.apache.poi.hwpf.HWPFDocument} only ever opens an existing one), and no test in this codebase
 * has needed one so far. {@link SupportedDocumentFormatsTest} already exercises {@code
 * application/msword} detection directly against the media type string.
 *
 * <p>The network path's decision is exercised through {@link UrlIndexingExecutor#decideForEntry}
 * itself, the exact call {@link UrlIndexingExecutor#execute} makes on a byte prefix before a full
 * download - not a reimplementation of it, so this test cannot silently drift from production.
 *
 * <p><b>ODF (#1057) is read from the same fixtures {@code SupportedDocumentFormatsTest} and {@code
 * TikaFallbackPipelineTest} use, not generated via POI</b> - POI is an OOXML/OLE2 library and has
 * no ODF writer, unlike the DOCX/PPTX cases above which POI builds from scratch.
 */
class DocumentFormatParityTest {

  @TempDir Path tempDir;

  private static final String PDF_MAGIC_BYTES = "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection";

  private static SupportedDocumentFormats.ContentDecision networkPathDecision(
      Path file, String entryName) throws IOException {
    byte[] fullContent = Files.readAllBytes(file);
    byte[] prefix =
        fullContent.length <= SupportedDocumentFormats.DETECTION_PREFIX_BYTES
            ? fullContent
            : Arrays.copyOf(fullContent, SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
    return UrlIndexingExecutor.decideForEntry(prefix, entryName);
  }

  @ParameterizedTest
  @ValueSource(strings = {"handbuch.md", "notiz.txt", "scan.png", "archiv.zip"})
  void bothIndexingPathsDecideAlikeForTheSameContent(String fileName) throws IOException {
    // Plain, human-readable text - accepted regardless of the (possibly misleading) name above,
    // except for the ones neither .md nor .txt (ambiguous text content only counts under one of
    // those two, see SupportedDocumentFormats#decideForFileName).
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, "Ganz gewöhnlicher, lesbarer Text.", StandardCharsets.UTF_8);

    boolean acceptedFromFilesystem = new DocumentService().isSupportedFormat(file);
    boolean acceptedFromNetwork = networkPathDecision(file, fileName).supported();

    assertThat(acceptedFromNetwork)
        .as(
            "'%s' must be treated identically by both indexing paths; filesystem says %s, "
                + "network says %s",
            fileName, acceptedFromFilesystem, acceptedFromNetwork)
        .isEqualTo(acceptedFromFilesystem);
  }

  @ParameterizedTest
  @ValueSource(strings = {"bescheid.pdf", "DATEI-IN-GROSSBUCHSTABEN.PDF"})
  void bothIndexingPathsAcceptAGenuinePdfUnderItsOwnExtension(String fileName) throws IOException {
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(new DocumentService().isSupportedFormat(file)).isTrue();
    var networkDecision = networkPathDecision(file, fileName);
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptAGenuineDocxUnderItsOwnExtension() throws IOException {
    // #404 review, finding 7: the strict Office types belong back in this test, not just the
    // mismatch/rejection cases - a genuine .docx accepted under its own, matching extension is the
    // baseline both other cases are contrasted against.
    Path file = tempDir.resolve("vermerk.docx");
    Files.write(file, realDocxBytes());

    assertThat(new DocumentService().isSupportedFormat(file)).isTrue();
    var networkDecision = networkPathDecision(file, "vermerk.docx");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptAGenuinePptxUnderItsOwnExtension() throws IOException {
    Path file = tempDir.resolve("folien.pptx");
    Files.write(file, realPptxBytes());

    assertThat(new DocumentService().isSupportedFormat(file)).isTrue();
    var networkDecision = networkPathDecision(file, "folien.pptx");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  // --- #1057: ODF is admitted the same way DOCX/PPTX are - both indexing paths must agree, and
  // the network path's own 64-KiB-prefix detection
  // (SupportedDocumentFormats#DETECTION_PREFIX_BYTES)
  // must resolve each ODF media type from a real file too, not just from a media-type string. --

  @ParameterizedTest
  @ValueSource(strings = {"odt", "ods", "odp"})
  void bothIndexingPathsAcceptAGenuineOdfFileUnderItsOwnExtension(String extension)
      throws IOException {
    String fileName = "dokument." + extension;
    Path file = tempDir.resolve(fileName);
    Files.write(file, realOdfBytes(extension));

    assertThat(new DocumentService().isSupportedFormat(file)).isTrue();
    var networkDecision = networkPathDecision(file, fileName);
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isFalse();
  }

  @Test
  void bothIndexingPathsAcceptReadableContentDespiteAWrongExtensionAndReportTheSameMismatch()
      throws IOException {
    // The core case #404 exists for: a real PDF mislabeled .csv used to be rejected outright on
    // both paths - now both accept it and both report the exact same detected extension.
    Path file = tempDir.resolve("bescheid.csv");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(new DocumentService().isSupportedFormat(file)).isTrue();

    var networkDecision = networkPathDecision(file, "bescheid.csv");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isTrue();
    assertThat(networkDecision.detectedExtension()).isEqualTo(".pdf");
  }

  @Test
  void bothIndexingPathsRejectUnsupportedContentEvenWithASupportedLookingExtension()
      throws IOException {
    Path file = tempDir.resolve("image.pdf");
    Files.write(file, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

    assertThat(new DocumentService().isSupportedFormat(file)).isFalse();
    assertThat(networkPathDecision(file, "image.pdf").supported()).isFalse();
  }

  // --- #404 review, finding 2: the RSS attachment path decides alike too --------------------

  @Test
  void theRssAttachmentPathAcceptsTheSameMislabeledPdfTheOtherTwoPathsDo() throws IOException {
    // Two things had to change for this to hold (#404 review, finding 2): AttachmentProfile.GENERIC
    // used to exclude a link like this from ever becoming a candidate at all (its own extension is
    // not one of SupportedDocumentFormats's six), and RssFeedIndexingExecutor#processAttachment
    // itself makes the actual accept/reject call the exact same way DocumentService and
    // UrlIndexingExecutor do, once a candidate's bytes are downloaded - see that method's own #404
    // comment.
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
    var filesystemDecision = new DocumentService().isSupportedFormat(file);
    var networkDecision = networkPathDecision(file, "bescheid.csv");

    assertThat(rssDecision.supported()).isTrue();
    assertThat(rssDecision.extensionMismatch()).isTrue();
    assertThat(rssDecision.detectedExtension()).isEqualTo(".pdf");
    assertThat(filesystemDecision).isEqualTo(rssDecision.supported());
    assertThat(networkDecision.supported()).isEqualTo(rssDecision.supported());
    assertThat(networkDecision.extensionMismatch()).isEqualTo(rssDecision.extensionMismatch());
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
}
