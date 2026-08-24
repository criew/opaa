package io.opaa.library;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.indexing.Document;

/**
 * Shared {@link Document} -&gt; {@link LibraryDocumentResponse} mapping, used by both {@link
 * KnowledgeLibraryService#listDocuments} and {@link LibraryDocumentService} (#420) so the two never
 * drift apart on which fields the API exposes.
 */
final class LibraryDocumentResponses {

  private LibraryDocumentResponses() {}

  /**
   * {@code folderPath} left {@code null} - for a caller that has not resolved it (or has no use for
   * it, e.g. {@link LibraryDocumentService#deleteDocument} never returns a body). {@link
   * #from(Document, String)} is the folder-aware counterpart (#821).
   */
  static LibraryDocumentResponse from(Document document) {
    return from(document, null);
  }

  /**
   * The folder-aware counterpart to {@link #from(Document)} (#821, Epic #520 Phase 2, ADR-0020):
   * {@code folderPath} is always derived by the caller (never stored), so it is passed in rather
   * than computed here - see {@code LibraryFolderPaths} for the two ways a caller derives it,
   * depending on whether it already has a whole library's folders loaded (a page of documents) or
   * only needs a single folder's path (one freshly uploaded document).
   */
  static LibraryDocumentResponse from(Document document, String folderPath) {
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
}
