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
}
