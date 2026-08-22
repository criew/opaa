package io.opaa.library;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentSourceType;

/**
 * Shared {@link Document} -&gt; {@link LibraryDocumentResponse} mapping, used by both {@link
 * KnowledgeLibraryService#listDocuments} and {@link LibraryDocumentService} (#420) so the two never
 * drift apart on which fields the API exposes.
 */
final class LibraryDocumentResponses {

  private LibraryDocumentResponses() {}

  static LibraryDocumentResponse from(Document document) {
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
        .sourceUrl(sourceUrl(document))
        .errorMessage(document.getErrorMessage());
  }

  /**
   * The deep link target for a document with no local file (#738): {@link Document#getFilePath()}
   * holds the remote URL itself for {@code HTTP_DIRECTORY} and {@code RSS_FEED} - the same identity
   * FileProcessingService#processUrlFile deduplicates by - but the server-local storage path for
   * {@code UPLOAD}/{@code FILESYSTEM}, which must stay internal (the same floor #507 already draws
   * for a library's own configured sourcePath).
   */
  private static String sourceUrl(Document document) {
    DocumentSourceType sourceType = document.getSourceType();
    if (sourceType == DocumentSourceType.HTTP_DIRECTORY
        || sourceType == DocumentSourceType.RSS_FEED) {
      return document.getFilePath();
    }
    return null;
  }
}
