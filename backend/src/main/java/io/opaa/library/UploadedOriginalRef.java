package io.opaa.library;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.document.Document;
import java.util.Optional;
import java.util.UUID;

/**
 * A reference to one uploaded original in {@link UploadedOriginalStore}: the organization and
 * library it belongs to and the locator {@code documents.file_path} carries for it. The locator's
 * form belongs to the adapter alone - no caller may parse, resolve or build one.
 *
 * @param organizationId the organization whose own storage area the original must lie in
 * @param libraryId the library whose own storage area inside that organization's must hold the
 *     original; every store operation rejects a locator that misses either segment (see {@link
 *     UploadedOriginalStore#belongsToLibrary})
 */
public record UploadedOriginalRef(UUID organizationId, UUID libraryId, String locator) {

  public UploadedOriginalRef {
    if (organizationId == null) {
      throw new IllegalArgumentException("organizationId is required");
    }
    if (libraryId == null) {
      throw new IllegalArgumentException("libraryId is required");
    }
    if (locator == null || locator.isBlank()) {
      throw new IllegalArgumentException("locator is required");
    }
  }

  /**
   * The reference to {@code document}'s uploaded original, or empty when the document has none at
   * all: only a {@code UPLOAD} row with a {@code file_path}, a library and an organization names
   * one. A {@code FILESYSTEM} or connector row names a file (or URL) this application does not own,
   * and must never be routed into the store - neither to read nor to delete.
   */
  public static Optional<UploadedOriginalRef> of(Document document) {
    if (document.getSourceType() != DocumentSourceType.UPLOAD
        || document.getFilePath() == null
        || document.getFilePath().isBlank()
        || document.getLibraryId() == null
        || document.getOrganizationId() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new UploadedOriginalRef(
            document.getOrganizationId(), document.getLibraryId(), document.getFilePath()));
  }
}
