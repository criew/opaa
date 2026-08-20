package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupportedDocumentFormatsTest {

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
}
