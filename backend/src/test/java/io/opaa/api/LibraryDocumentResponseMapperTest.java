package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibraryDocumentPageResponse;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.Document;
import io.opaa.library.LibraryDocumentEntry;
import io.opaa.library.LibraryDocumentPage;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderChild;
import java.util.List;
import java.util.UUID;
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

  // #821: the folder-aware overload - folderPath is always resolved by the caller
  // (LibraryFolderPaths),
  // never stored, so this pins that the mapper simply carries it through onto the response.
  @Test
  void toResponseFromADocumentEntryCarriesTheResolvedFolderPathThrough() {
    Document document =
        new Document(
            "protokoll.pdf",
            "/data/protokoll.pdf",
            "application/pdf",
            1024L,
            DocumentSourceType.UPLOAD);
    LibraryDocumentEntry entry = new LibraryDocumentEntry(document, "Protokolle/2026");

    LibraryDocumentResponse response = LibraryDocumentResponseMapper.toResponse(entry);

    assertThat(response.getId()).isEqualTo(document.getId());
    assertThat(response.getFileName()).isEqualTo("protokoll.pdf");
    assertThat(response.getFolderId()).isEqualTo(document.getFolderId());
    assertThat(response.getFolderPath()).isEqualTo("Protokolle/2026");
  }

  @Test
  void toResponseFromADocumentEntryLeavesFolderPathNullForTheLibrarysRoot() {
    Document document =
        new Document(
            "wurzel.pdf", "/data/wurzel.pdf", "application/pdf", 512L, DocumentSourceType.UPLOAD);
    LibraryDocumentEntry entry = new LibraryDocumentEntry(document, null);

    assertThat(LibraryDocumentResponseMapper.toResponse(entry).getFolderPath()).isNull();
  }

  @Test
  void toPageResponseCarriesDocumentsFoldersBreadcrumbAndFolderIdThrough() {
    UUID organizationId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    LibraryFolder root = new LibraryFolder(libraryId, null, "Protokolle", organizationId);
    LibraryFolder year = new LibraryFolder(libraryId, root.getId(), "2026", organizationId);
    Document document =
        new Document(
            "sitzung.pdf",
            "/data/sitzung.pdf",
            "application/pdf",
            2048L,
            DocumentSourceType.UPLOAD);
    LibraryDocumentEntry entry = new LibraryDocumentEntry(document, "Protokolle/2026");
    LibraryFolderChild folderChild = new LibraryFolderChild(year, 3L);
    LibraryDocumentPage page =
        new LibraryDocumentPage(
            List.of(entry), 0, 20, 1L, List.of(folderChild), List.of(root, year), year.getId());

    LibraryDocumentPageResponse response = LibraryDocumentResponseMapper.toPageResponse(page);

    assertThat(response.getItems()).hasSize(1);
    assertThat(response.getItems().get(0).getFileName()).isEqualTo("sitzung.pdf");
    assertThat(response.getItems().get(0).getFolderPath()).isEqualTo("Protokolle/2026");
    assertThat(response.getPage()).isZero();
    assertThat(response.getSize()).isEqualTo(20);
    assertThat(response.getTotalElements()).isEqualTo(1L);
    assertThat(response.getFolders()).hasSize(1);
    assertThat(response.getFolders().get(0).getId()).isEqualTo(year.getId());
    assertThat(response.getFolders().get(0).getName()).isEqualTo("2026");
    assertThat(response.getFolders().get(0).getDocumentCount()).isEqualTo(3L);
    // #821: breadcrumb is root-first, ending with the browsed folder itself - two levels deep here.
    assertThat(response.getBreadcrumb()).hasSize(2);
    assertThat(response.getBreadcrumb().get(0).getId()).isEqualTo(root.getId());
    assertThat(response.getBreadcrumb().get(0).getName()).isEqualTo("Protokolle");
    assertThat(response.getBreadcrumb().get(1).getId()).isEqualTo(year.getId());
    assertThat(response.getBreadcrumb().get(1).getName()).isEqualTo("2026");
    assertThat(response.getFolderId()).isEqualTo(year.getId());
  }

  @Test
  void toPageResponseLeavesFolderIdNullAndListsEmptyWhileSearching() {
    // #821: q ignores folderId entirely and stays bibliotheksweit - folders/breadcrumb are always
    // empty for a search hit list, and folderId itself stays null.
    LibraryDocumentPage page =
        new LibraryDocumentPage(List.of(), 0, 20, 0L, List.of(), List.of(), null);

    LibraryDocumentPageResponse response = LibraryDocumentResponseMapper.toPageResponse(page);

    assertThat(response.getItems()).isEmpty();
    assertThat(response.getFolders()).isEmpty();
    assertThat(response.getBreadcrumb()).isEmpty();
    assertThat(response.getFolderId()).isNull();
  }
}
