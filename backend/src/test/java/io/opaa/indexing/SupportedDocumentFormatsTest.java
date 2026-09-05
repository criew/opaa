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

  // The correct OOXML/OLE2 media types below depend on the transitive
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

  // --- ODF (ODT, ODS, ODP) ---------------------------------------------------------------

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

  // Pins DOCX and ODT apart from each other the same way the OOXML-container
  // tests above pin real Office content apart from its generic, unresolved container type - so a
  // later "tolerance" widening of either extension's strict set cannot silently start accepting
  // the other family's content.
  @Test
  void contentMatchesExtensionRejectsDocxContentForOdtAndOdtContentForDocx() {
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(
                ".odt", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .isFalse();
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(
                ".docx", "application/vnd.oasis.opendocument.text"))
        .isFalse();
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
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".csv", "text/plain")).isTrue();
    // application/xml and application/rtf are declared sub-class-of text/plain in Tika's own
    // media type registry (tika-mimetypes.xml) - exactly the false positives this check
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
    // application/x-tika-msoffice is the generic OLE2 fallback Tika
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

  // --- content decides, the extension is only a hint -----------------------------------

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
    // Deliberately not resolved here: content alone cannot tell a Markdown file apart from
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
  void decideForFileNameAcceptsMatchingExtensionWithoutAMismatch() {
    var decision = SupportedDocumentFormats.decideForFileName("bescheid.pdf", "application/pdf");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".pdf");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  @Test
  void decideForFileNameAcceptsAndReportsAWrongExtensionOnReadableContent() {
    // The core case content-based admission exists for: a spreadsheet-turned-.txt would be indexed
    // as garbled
    // text; here the reverse - a real PDF mislabeled .csv - would be rejected outright. Both
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
  void decideForFileNameAcceptsCsvContentOnlyUnderItsOwnExtension() {
    // CSV joins .md/.txt as text-tolerant - its own extension has to already claim it,
    // exactly like the other two, since content alone cannot tell a CSV export apart from
    // Markdown or plain text.
    var decision = SupportedDocumentFormats.decideForFileName("gebuehren.csv", "text/plain");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".csv");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  @Test
  void decideForFileNameRejectsAmbiguousTextContentUnderAnUnrelatedExtension() {
    // the extension is consulted for ambiguous (text) content, not just for reporting a
    // mismatch - a log file or source code carrying genuinely readable text must not silently
    // widen the accepted Bestand to "any plain text whatsoever". (CSV itself is a text-tolerant
    // extension, see decideForFileNameAcceptsCsvContentOnlyUnderItsOwnExtension.)
    var decision = SupportedDocumentFormats.decideForFileName("export.log", "text/plain");

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

  // --- EML and MSG ------------------------------------------------------------------

  @Test
  void isSupportedAcceptsEmlAndMsgByName() {
    assertThat(SupportedDocumentFormats.isSupported("vorgang.eml")).isTrue();
    assertThat(SupportedDocumentFormats.isSupported("vorgang.msg")).isTrue();
  }

  @Test
  void contentMatchesExtensionAcceptsTheExactMediaTypeForMsg() {
    assertThat(
            SupportedDocumentFormats.contentMatchesExtension(".msg", "application/vnd.ms-outlook"))
        .isTrue();
  }

  @Test
  void contentMatchesExtensionAcceptsEmlOnlyAsTextTolerantContent() {
    // message/rfc822 is a text/plain specialization in Tika's own media
    // type hierarchy, not a distinctive byte signature - .eml is admitted the same tolerant way
    // .md/.txt/.csv are, not as a strictly detected type.
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".eml", "message/rfc822")).isTrue();
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".eml", "text/plain")).isTrue();
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".eml", "application/pdf"))
        .isFalse();
  }

  @Test
  void decideForFileNameAcceptsMsgContentRegardlessOfExtension() {
    var msg =
        SupportedDocumentFormats.decideForFileName("vorgang.msg", "application/vnd.ms-outlook");
    assertThat(msg.supported()).isTrue();
    assertThat(msg.detectedExtension()).isEqualTo(".msg");
    assertThat(msg.extensionMismatch()).isFalse();
  }

  @Test
  void decideForFileNameAcceptsEmlOnlyUnderItsOwnExtension() {
    var wrongExtension =
        SupportedDocumentFormats.decideForFileName("vorgang.pdf", "message/rfc822");
    assertThat(wrongExtension.supported()).isFalse();

    var eml = SupportedDocumentFormats.decideForFileName("vorgang.eml", "message/rfc822");
    assertThat(eml.supported()).isTrue();
    assertThat(eml.detectedExtension()).isEqualTo(".eml");
    assertThat(eml.extensionMismatch()).isFalse();
  }

  @Test
  void decideForFileNameDoesNotRouteUnrelatedTextFilesIntoTheMailPipelineEvenIfTikaGuessesRfc822() {
    // a log file, a Markdown changelog and
    // a CSV export can all trip Tika's loose message/rfc822 header-line heuristic, but each keeps
    // its own extension's routing because decideForFileName's text-tolerant branch always
    // classifies
    // by the file's own claimed extension, never by which text-tolerant type the content merely
    // resembles.
    var log = SupportedDocumentFormats.decideForFileName("system.log", "message/rfc822");
    assertThat(log.supported()).isFalse();

    var markdown = SupportedDocumentFormats.decideForFileName("protokoll.md", "message/rfc822");
    assertThat(markdown.supported()).isTrue();
    assertThat(markdown.detectedExtension()).isEqualTo(".md");

    var csv = SupportedDocumentFormats.decideForFileName("export.csv", "message/rfc822");
    assertThat(csv.supported()).isTrue();
    assertThat(csv.detectedExtension()).isEqualTo(".csv");
  }

  @Test
  void decideForFileNameAcceptsARealEmlEvenWhenTikaDoesNotRecognizeItsFirstHeaderLine() {
    // an .eml whose first header does not match Tika's rfc822 heuristic
    // (e.g. a leading Authentication-Results: or German Von:/An: pair) can be detected as plain
    // text/plain instead of message/rfc822 - still admitted, since .eml only demands text-tolerant
    // content, not the rfc822 heuristic specifically.
    var eml = SupportedDocumentFormats.decideForFileName("weiterleitung.eml", "text/plain");
    assertThat(eml.supported()).isTrue();
    assertThat(eml.detectedExtension()).isEqualTo(".eml");
  }

  @Test
  void detectMediaTypeDetectsPlainText() throws IOException {
    Path file = tempDir.resolve("data.bin");
    Files.writeString(file, "Ganz gewöhnlicher Text ohne besondere Bytes.");

    assertThat(SupportedDocumentFormats.detectMediaType(file)).startsWith("text/plain");
  }

  // --- HTML ------------------------------------------------------------------------------

  @Test
  void contentMatchesExtensionAcceptsHtmlAndXhtmlForTheHtmlExtension() {
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".html", "text/html")).isTrue();
    assertThat(SupportedDocumentFormats.contentMatchesExtension(".html", "application/xhtml+xml"))
        .isTrue();
  }

  @Test
  void extensionForDetectedContentResolvesHtml() {
    assertThat(SupportedDocumentFormats.extensionForDetectedContent("text/html"))
        .isEqualTo(".html");
  }

  @Test
  void decideForFileNameAcceptsHtmlContentRegardlessOfExtension() {
    // Routing (ingestion-pipelines.md, Teil 3, Punkt 4): HTML is admitted purely from its detected
    // content, exactly like PDF/DOCX - the file's own extension only decides the mismatch flag.
    var decision = SupportedDocumentFormats.decideForFileName("seite.htm", "text/html");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".html");
    assertThat(decision.extensionMismatch()).isTrue();
  }

  @Test
  void isSupportedAcceptsHtmlByName() {
    assertThat(SupportedDocumentFormats.isSupported("seite.html")).isTrue();
  }

  @Test
  void detectMediaTypeReadsHtmlFromARealFile() throws IOException {
    Path file = tempDir.resolve("seite.html");
    Files.writeString(file, "<html><body><main><h1>Titel</h1><p>Inhalt.</p></main></body></html>");

    assertThat(SupportedDocumentFormats.detectMediaType(file)).isEqualTo("text/html");
  }

  @Test
  void decideForFileNameKeepsTheMarkdownRuleWinningOverAHtmlContentDetection() throws IOException {
    // Tika's tika-mimetypes.xml registers text/html as a
    // specialization of text/plain, so a Markdown file that happens to open with a raw
    // <div>/<h1> is detected as text/html, not text/plain - confirmed empirically against the
    // real Tika detector below, not assumed from a literal mime string. Without the fix, the
    // strict (HTML) branch would win over the Markdown/Klartext/CSV special rule
    // (ingestion-pipelines.md, Teil 1, "gilt für das Routing unverändert weiter"), silently
    // routing the file to HtmlDocumentPipeline with no FORMAT_MISMATCH ever reported (the
    // content passing contentMatchesExtension(".md", "text/html") means the strict branch's own
    // mismatch check comes out false too).
    Path file = tempDir.resolve("readme.md");
    Files.writeString(
        file, "<div><h1>Nicht wirklich Markdown</h1><p>Text</p></div>", StandardCharsets.UTF_8);
    String detected = SupportedDocumentFormats.detectMediaType(file);
    assertThat(detected).isEqualTo("text/html");

    var decision = SupportedDocumentFormats.decideForFileName("readme.md", detected);

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".md");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  @Test
  void decideForFileNameKeepsTheKlartextRuleWinningOverAHtmlContentDetection() {
    // Same rule for .txt as for .md above - the special rule covers all three text-tolerant
    // extensions (.md/.txt/.csv), not just Markdown.
    var decision = SupportedDocumentFormats.decideForFileName("notiz.txt", "text/html");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".txt");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  @Test
  void decideForFileNameKeepsTheEmlRuleWinningOverAHtmlContentDetection() {
    // .eml joined TEXT_TOLERANT_EXTENSIONS already
    // established that the text-tolerant branch must win over a strict detection - the same
    // rationale applies here without any change to decideForFileName itself. An HTML-formatted
    // mail body (common for a genuine .eml exported as raw markup, or one saved without its own
    // MIME headers) detects as text/html, exactly the Markdown/Klartext case above - the file's
    // own .eml extension decides, not the content, so it is routed to MailDocumentPipeline rather
    // than silently to HtmlDocumentPipeline with no FORMAT_MISMATCH reported. A file genuinely
    // named .html with the same content is unaffected (own extension is not text-tolerant, so it
    // still takes the strict branch and reaches HtmlDocumentPipeline as normal) - only a file
    // already claiming .eml benefits from this priority.
    var decision = SupportedDocumentFormats.decideForFileName("nachricht.eml", "text/html");

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".eml");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  // --- a bounded prefix that ends inside an unresolved container is not a rejection ----

  @Test
  void decideForPrefixDecidesOnThePrefixAloneWheneverTheDetectionResolved() throws Exception {
    // The bandwidth guard the prefix decision exists for: a detection that resolved - here a PNG
    // signature, resolved to image/png and simply not among the accepted formats - is final on the
    // sample, so an entry this system does not want is never transferred in full.
    byte[] prefix = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    var decision =
        SupportedDocumentFormats.decideForPrefix(
            "scan.pdf",
            prefix,
            () -> {
              throw new AssertionError("the complete file must not be fetched for a PNG prefix");
            });

    assertThat(decision.supported()).isFalse();
  }

  @Test
  void decideForPrefixFallsBackToTheCompleteFileForAnUnresolvedContainerPrefix() throws Exception {
    // An OLE2 container whose directory sector sits past the sample detects only as the generic
    // application/x-tika-msoffice - the complete file decides instead of the entry being rejected.
    assertThat(SupportedDocumentFormats.isUnresolvedContainerType("application/x-tika-msoffice"))
        .isTrue();
    byte[] msg = Files.readAllBytes(msgFixture());
    byte[] prefix = java.util.Arrays.copyOf(msg, SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
    assertThat(SupportedDocumentFormats.detectMediaType(prefix))
        .as("the fixture must actually reproduce the unresolved-container detection")
        .isEqualTo("application/x-tika-msoffice");

    var decision =
        SupportedDocumentFormats.decideForPrefix("vorgang.msg", prefix, this::msgFixture);

    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".msg");
    assertThat(decision.extensionMismatch()).isFalse();
  }

  private Path msgFixture() throws IOException {
    Path file = tempDir.resolve("vorgang.msg");
    if (Files.exists(file)) {
      return file;
    }
    try (var in =
        SupportedDocumentFormatsTest.class
            .getClassLoader()
            .getResourceAsStream("test-documents/mail/attachment_msg_pdf.msg")) {
      assertThat(in).as("Test resource must exist").isNotNull();
      Files.copy(in, file);
    }
    return file;
  }
}
