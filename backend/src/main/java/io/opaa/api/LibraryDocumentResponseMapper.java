package io.opaa.api;

import io.opaa.api.dto.LibraryDocumentPageResponse;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.dto.LibraryFolderBreadcrumbItem;
import io.opaa.api.dto.LibraryFolderListItem;
import io.opaa.indexing.Document;
import io.opaa.library.LibraryDocumentEntry;
import io.opaa.library.LibraryDocumentPage;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderChild;

/**
 * Maps {@link Document}, {@link LibraryDocumentEntry} and {@link LibraryDocumentPage} onto their
 * generated response counterparts (ADR-0006: API DTOs are generated from the specification, never
 * hand-written). Formerly {@code io.opaa.library.LibraryDocumentResponses} (#420), moved here as
 * part of #860 so a domain service never builds a generated response itself.
 */
final class LibraryDocumentResponseMapper {

  private LibraryDocumentResponseMapper() {}

  /**
   * {@code folderPath} left {@code null} - for a caller that has not resolved it (or has no use for
   * it, e.g. {@code LibraryDocumentService#deleteDocument} never returns a body). {@link
   * #toResponse(LibraryDocumentEntry)} is the folder-aware counterpart (#821).
   */
  static LibraryDocumentResponse toResponse(Document document) {
    return toResponse(document, null);
  }

  static LibraryDocumentResponse toResponse(LibraryDocumentEntry entry) {
    return toResponse(entry.document(), entry.folderPath());
  }

  private static LibraryDocumentResponse toResponse(Document document, String folderPath) {
    return new LibraryDocumentResponse(
            document.getId(),
            document.getFileName(),
            document.getStatus(),
            document.getSourceType(),
            document.getChunkCount())
        .contentType(document.getContentType())
        .fileSize(document.getFileSize())
        .indexedAt(document.getIndexedAt())
        .uploadedByUserId(document.getUploadedByUserId())
        .sourceEntryUrl(document.getSourceEntryUrl())
        .sourceUrl(document.getDeepLinkSourceUrl())
        .errorMessage(document.getErrorMessage())
        .folderId(document.getFolderId())
        .folderPath(folderPath);
  }

  static LibraryDocumentPageResponse toPageResponse(LibraryDocumentPage page) {
    return new LibraryDocumentPageResponse(
            page.documents().stream().map(LibraryDocumentResponseMapper::toResponse).toList(),
            page.page(),
            page.size(),
            page.totalElements(),
            page.folders().stream().map(LibraryDocumentResponseMapper::toFolderListItem).toList(),
            page.breadcrumb().stream()
                .map(LibraryDocumentResponseMapper::toBreadcrumbItem)
                .toList())
        .folderId(page.folderId());
  }

  private static LibraryFolderListItem toFolderListItem(LibraryFolderChild child) {
    return new LibraryFolderListItem(
        child.folder().getId(), child.folder().getName(), child.documentCount());
  }

  private static LibraryFolderBreadcrumbItem toBreadcrumbItem(LibraryFolder folder) {
    return new LibraryFolderBreadcrumbItem(folder.getId(), folder.getName());
  }
}
