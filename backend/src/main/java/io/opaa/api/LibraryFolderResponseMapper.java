package io.opaa.api;

import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderDetail;

/**
 * Maps {@link LibraryFolderDetail} onto its generated response counterpart (ADR-0006: API DTOs are
 * generated from the specification, never hand-written).
 */
final class LibraryFolderResponseMapper {

  private LibraryFolderResponseMapper() {}

  static LibraryFolderResponse toResponse(LibraryFolderDetail detail) {
    LibraryFolder folder = detail.folder();
    return new LibraryFolderResponse(
            folder.getId(),
            folder.getLibraryId(),
            folder.getName(),
            detail.documentCount(),
            folder.getCreatedAt())
        .parentFolderId(folder.getParentFolderId());
  }
}
