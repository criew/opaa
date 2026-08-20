package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentSourceType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LibraryDocumentResponses} (#493): {@code sourceEntryUrl} must round-trip
 * from {@link Document} into {@link LibraryDocumentResponse} exactly like the fields #420/#434
 * already established this mapper for ({@code uploadedByUserId}, {@code errorMessage}) - present
 * when the document carries one (an RSS attachment, #468), {@code null} for every document that
 * does not.
 */
class LibraryDocumentResponsesTest {

  @Test
  void carriesSourceEntryUrlFromAnRssAttachmentIntoTheResponse() {
    Document document =
        new Document(
            "dienstanweisung-anlage.pdf",
            "https://example.gov/dl/anlage.pdf",
            "application/pdf",
            2048L,
            DocumentSourceType.RSS_FEED);
    document.setSourceEntryUrl("https://example.gov/aktuelles/dienstanweisung-2024");

    LibraryDocumentResponse response = LibraryDocumentResponses.from(document);

    assertThat(response.getSourceEntryUrl())
        .isEqualTo("https://example.gov/aktuelles/dienstanweisung-2024");
  }

  @Test
  void leavesSourceEntryUrlNullForAnUploadedDocument() {
    Document document =
        new Document(
            "dienstanweisung-2024.pdf",
            "/data/dienstanweisung-2024.pdf",
            "application/pdf",
            2048L,
            DocumentSourceType.UPLOAD);

    LibraryDocumentResponse response = LibraryDocumentResponses.from(document);

    assertThat(response.getSourceEntryUrl()).isNull();
  }
}
