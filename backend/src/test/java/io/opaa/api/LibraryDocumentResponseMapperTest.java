package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentSourceType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LibraryDocumentResponseMapper} (#493, moved here from {@code
 * io.opaa.library.LibraryDocumentResponsesTest} by #860): {@code sourceEntryUrl} must round-trip
 * from {@link Document} into {@link LibraryDocumentResponse} exactly like the fields #420/#434
 * already established this mapper for ({@code uploadedByUserId}, {@code errorMessage}) - present
 * when the document carries one (an RSS attachment, #468), {@code null} for every document that
 * does not.
 */
class LibraryDocumentResponseMapperTest {

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

    LibraryDocumentResponse response = LibraryDocumentResponseMapper.toResponse(document);

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

    LibraryDocumentResponse response = LibraryDocumentResponseMapper.toResponse(document);

    assertThat(response.getSourceEntryUrl()).isNull();
  }

  // #738: HTTP_DIRECTORY carries no local file behind GET .../content (that endpoint answers 404
  // for it) - the frontend needs the document's own remote location instead to open it.
  @Test
  void carriesFilePathAsSourceUrlForAnHttpDirectoryDocument() {
    Document document =
        new Document(
            "dienstanweisung-2024.pdf",
            "https://example.gov/verzeichnis/dienstanweisung-2024.pdf",
            "application/pdf",
            2048L,
            DocumentSourceType.HTTP_DIRECTORY);

    LibraryDocumentResponse response = LibraryDocumentResponseMapper.toResponse(document);

    assertThat(response.getSourceUrl())
        .isEqualTo("https://example.gov/verzeichnis/dienstanweisung-2024.pdf");
  }

  // #738: an RSS attachment's filePath is its own remote file (not the entry page) - it is still
  // exposed as sourceUrl for completeness, but a frontend caller prefers sourceEntryUrl (asserted
  // separately above) since the entry page is more useful for tracing an attachment back to its
  // origin than the raw attachment URL.
  @Test
  void carriesFilePathAsSourceUrlForAnRssFeedDocument() {
    Document document =
        new Document(
            "rundschreiben.pdf",
            "https://example.gov/feed/rundschreiben.pdf",
            "application/pdf",
            2048L,
            DocumentSourceType.RSS_FEED);

    LibraryDocumentResponse response = LibraryDocumentResponseMapper.toResponse(document);

    assertThat(response.getSourceUrl()).isEqualTo("https://example.gov/feed/rundschreiben.pdf");
  }

  // #738: the server-local storage path must never leak through this field for UPLOAD/FILESYSTEM.
  @Test
  void leavesSourceUrlNullForAnUploadedDocument() {
    Document document =
        new Document(
            "dienstanweisung-2024.pdf",
            "/data/dienstanweisung-2024.pdf",
            "application/pdf",
            2048L,
            DocumentSourceType.UPLOAD);

    LibraryDocumentResponse response = LibraryDocumentResponseMapper.toResponse(document);

    assertThat(response.getSourceUrl()).isNull();
  }
}
