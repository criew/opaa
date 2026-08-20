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
        .errorMessage(document.getErrorMessage());
  }
}
