package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportedDocumentFormatsTest {

  @TempDir Path tempDir;

  @Test
  void extensionForContentTypeResolvesKnownTypes() {
    assertThat(SupportedDocumentFormats.extensionForContentType("application/pdf"))
        .isEqualTo(".pdf");
    assertThat(SupportedDocumentFormats.extensionForContentType("application/pdf; charset=binary"))
        .isEqualTo(".pdf");
    assertThat(SupportedDocumentFormats.extensionForContentType("text/plain")).isEqualTo(".txt");
  }

  @Test
  void extensionForContentTypeReturnsNullForUnknownOrMissingType() {
    assertThat(SupportedDocumentFormats.extensionForContentType("application/octet-stream"))
        .isNull();
    assertThat(SupportedDocumentFormats.extensionForContentType(null)).isNull();
  }

  // #435 code review: the correct OOXML/OLE2 media types below depend on the transitive
  // tika-parsers-standard detectors (via spring-ai-tika-document-reader) actually being on the
  // classpath - without them, Tika's ZipContainerDetector/POIFSContainerDetector cannot look
  // inside the container and falls back to the generic "application/x-tika-ooxml"/
  // "application/x-tika-msoffice" container types instead of the specific format. Pinning both the
  // accepted specific type and the rejected generic fallback here means a future Spring AI bump
  // that trims those parsers breaks these tests loudly instead of silently rejecting every real
  // Office upload in production.
  @Test
  void contentMatchesExtensionAcceptsTheExactMediaTypeForEveryStrictExtension() {
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".pdf", "application/pdf"))
        .isTrue();
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".doc", "application/msword"))
        .isTrue();
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(
                ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .isTrue();
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(
                ".pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        .isTrue();
  }

  // --- #1057: ODF (ODT, ODS, ODP) ---------------------------------------------------------------

  @Test
  void contentMatchesExtensionAcceptsTheExactMediaTypeForEveryOdfExtension() {
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(
                ".odt", "application/vnd.oasis.opendocument.text"))
        .isTrue();
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(
                ".ods", "application/vnd.oasis.opendocument.spreadsheet"))
        .isTrue();
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(
                ".odp", "application/vnd.oasis.opendocument.presentation"))
        .isTrue();
  }

  @Test
  void extensionForDetectedContentResolvesEveryOdfType() {
    assertThat(
            SupportedDocumentFormats.extensionForDetectedContent(
                "application/vnd.oasis.opendocument.text"))
        .isEqualTo(".odt");
    assertThat(
            SupportedDocumentFormats.extensionForDetectedContent(
                "application/vnd.oasis.opendocument.spreadsheet"))
        .isEqualTo(".ods");
    assertThat(
            SupportedDocumentFormats.extensionForDetectedContent(
                "application/vnd.oasis.opendocument.presentation"))
        .isEqualTo(".odp");
  }

  @Test
  void decideForFileNameAcceptsOdfContentRegardlessOfExtension() {
    // Routing (ingestion-pipelines.md, Teil 3, Punkt 2): ODT is admitted purely from its detected
    // content, exactly like DOCX - the file's own extension only decides the mismatch flag.
    var decision =
        SupportedDocumentFormats.decideForFileName(
            "satzung.odt", "application/vnd.oasis.opendocument.text");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".odt");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  @Test
  void isSupportedAcceptsEveryOdfExtensionByName() {
    assertThat(SupportedDocumentFormats.isSupported("satzung.odt")).isTrue();
    assertThat(SupportedDocumentFormats.isSupported("haushalt.ods")).isTrue();
    assertThat(SupportedDocumentFormats.isSupported("vortrag.odp")).isTrue();
  }

  @Test
  void detectMediaTypeReadsEveryOdfFormatFromARealFixture() throws IOException {
    assertThat(SupportedDocumentFormats.detectMediaType(testResource("test-document.odt")))
        .isEqualTo("application/vnd.oasis.opendocument.text");
    assertThat(SupportedDocumentFormats.detectMediaType(testResource("test-document.ods")))
        .isEqualTo("application/vnd.oasis.opendocument.spreadsheet");
    assertThat(SupportedDocumentFormats.detectMediaType(testResource("test-document.odp")))
        .isEqualTo("application/vnd.oasis.opendocument.presentation");
  }

  private Path testResource(String name) throws IOException {
    Path file = tempDir.resolve(name);
    try (var in = getClass().getClassLoader().getResourceAsStream("test-documents/" + name)) {
      assertThat(in).as("Test resource %s must exist", name).isNotNull();
      Files.copy(in, file);
    }
    return file;
  }

  @Test
  void contentMatchesExtensionAcceptsAnyTextSpecializationForTheTextTolerantExtensions() {
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".txt", "text/plain")).isTrue();
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".md", "text/plain")).isTrue();
    // application/xml and application/rtf are declared sub-class-of text/plain in Tika's own
    // media type registry (tika-mimetypes.xml) - exactly the false positives #435's code review
    // flagged a plain startsWith("text/") check as missing.
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".txt", "application/xml"))
        .isTrue();
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".txt", "application/rtf"))
        .isTrue();
  }

  @Test
  void contentMatchesExtensionRejectsAGenericUnresolvedOoxmlContainerForDocxAndPptx() {
    // A ZIP archive Tika could not further classify as a specific OOXML format - the fallback a
    // trimmed tika-parsers-standard would produce for every real .docx/.pptx upload (see the class
    // comment above). Must not be tolerated the way the text formats tolerate a generic subtype.
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(".docx", "application/x-tika-ooxml"))
        .isFalse();
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(".pptx", "application/x-tika-ooxml"))
        .isFalse();
  }

  @Test
  void contentMatchesExtensionRejectsAGenericUnresolvedOle2ContainerForDoc() {
    // #435 code review, finding 3: application/x-tika-msoffice is the generic OLE2 fallback Tika
    // uses when it cannot identify the specific format inside the container - deliberately not
    // accepted for .doc (see STRICT_CONTENT_TYPES_BY_EXTENSION's Javadoc for why).
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(".doc", "application/x-tika-msoffice"))
        .isFalse();
  }

  @Test
  void contentMatchesExtensionRejectsBinaryContentForTheTextTolerantExtensions() {
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".txt", "application/pdf"))
        .isFalse();
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".md", "application/zip"))
        .isFalse();
  }

  @Test
  void contentMatchesExtensionRejectsAMissingDetectionResult() {
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".pdf", null)).isFalse();
  }

  // --- #404: content decides, the extension is only a hint -----------------------------------

  @Test
  void extensionForDetectedContentResolvesEveryStrictType() {
    assertThat(SupportedDocumentFormats.extensionForDetectedContent("application/pdf"))
        .isEqualTo(".pdf");
    assertThat(SupportedDocumentFormats.extensionForDetectedContent("application/msword"))
        .isEqualTo(".doc");
    assertThat(
            SupportedDocumentFormats.extensionForDetectedContent(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .isEqualTo(".docx");
  }

  @Test
  void extensionForDetectedContentReturnsNullForAmbiguousTextContent() {
    // Deliberately not resolved here (#404): content alone cannot tell a Markdown file apart from
    // a CSV export or a source file - see decideForFileName for how the ambiguity is resolved
    // using the file's own name as a hint instead.
    assertThat(SupportedDocumentFormats.extensionForDetectedContent("text/plain")).isNull();
  }

  @Test
  void extensionForDetectedContentReturnsNullForUnsupportedOrMissingContent() {
    assertThat(SupportedDocumentFormats.extensionForDetectedContent("application/zip")).isNull();
    assertThat(SupportedDocumentFormats.extensionForDetectedContent(null)).isNull();
  }

  @Test
  void isPdfContentIsTrueOnlyForDetectedPdfContent() {
    assertThat(SupportedDocumentFormats.isPdfContent("application/pdf")).isTrue();
    assertThat(SupportedDocumentFormats.isPdfContent("application/msword")).isFalse();
    assertThat(SupportedDocumentFormats.isPdfContent("text/plain")).isFalse();
    assertThat(SupportedDocumentFormats.isPdfContent(null)).isFalse();
  }

  @Test
  void decideForFileNameAcceptsMatchingExtensionWithoutAMismatch() {
    var decision = SupportedDocumentFormats.decideForFileName("bescheid.pdf", "application/pdf");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".pdf");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  @Test
  void decideForFileNameAcceptsAndReportsAWrongExtensionOnReadableContent() {
    // The core case #404 exists for: a spreadsheet-turned-.txt used to be indexed as garbled
    // text; here the reverse - a real PDF mislabeled .csv - used to be rejected outright. Both
    // are now accepted from their actual content, with the mismatch surfaced, not hidden.
    var decision = SupportedDocumentFormats.decideForFileName("bescheid.csv", "application/pdf");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".pdf");
    assertThat(decision.extensionMismatch()).isTrue();
  }

  @Test
  void decideForFileNameAcceptsAFileWithNoRecognizedExtensionAtAllAsAMismatch() {
    var decision = SupportedDocumentFormats.decideForFileName("bescheid", "application/pdf");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.extensionMismatch()).isTrue();
  }

  @Test
  void decideForFileNameToleratesMdContentClaimedAsTxtAndViceVersa() {
    assertThat(
            SupportedDocumentFormats.decideForFileName("notes.md", "text/plain")
                .extensionMismatch())
        .isFalse();
    assertThat(
            SupportedDocumentFormats.decideForFileName("notes.txt", "text/plain")
                .extensionMismatch())
        .isFalse();
  }

  @Test
  void decideForFileNameRejectsAmbiguousTextContentUnderAnUnrelatedExtension() {
    // #404: the extension is consulted for ambiguous (text) content, not just for reporting a
    // mismatch - a CSV export, a log file or source code carrying genuinely readable text must not
    // silently widen the accepted Bestand to "any plain text whatsoever".
    var decision = SupportedDocumentFormats.decideForFileName("export.csv", "text/plain");

    assertThat(decision.supported()).isFalse();
  }

  @Test
  void decideForFileNameRejectsAmbiguousTextContentWithNoExtensionAtAll() {
    var decision = SupportedDocumentFormats.decideForFileName("README", "text/plain");

    assertThat(decision.supported()).isFalse();
  }

  @Test
  void decideForFileNameRejectsUnsupportedContentRegardlessOfExtension() {
    var decision = SupportedDocumentFormats.decideForFileName("scan.pdf", "image/png");

    assertThat(decision.supported()).isFalse();
    assertThat(decision.detectedExtension()).isNull();
    assertThat(decision.extensionMismatch()).isFalse();
  }

  @Test
  void detectMediaTypeReadsContentAloneIgnoringTheFileName() throws IOException {
    // Named .csv, but Tika detects it purely from the bytes - the magic string "%PDF-" is
    // sufficient for PDF's own magic-byte match (no full PDF structure needed).
    Path file = tempDir.resolve("bescheid.csv");
    Files.write(
        file, "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection".getBytes(StandardCharsets.UTF_8));

    assertThat(SupportedDocumentFormats.detectMediaType(file)).isEqualTo("application/pdf");
  }

  @Test
  void detectMediaTypeDetectsPlainText() throws IOException {
    Path file = tempDir.resolve("data.bin");
    Files.writeString(file, "Ganz gewöhnlicher Text ohne besondere Bytes.");

    assertThat(SupportedDocumentFormats.detectMediaType(file)).startsWith("text/plain");
  }
}
