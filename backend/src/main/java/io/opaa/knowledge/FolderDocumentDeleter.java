package io.opaa.knowledge;

import io.opaa.auth.CurrentUser;
import java.util.UUID;

/**
 * Deletes one document of a folder that {@link LibraryFolderService#deleteFolder} removes, with the
 * same permission check and file, chunk and row cleanup as deleting that document on its own. The
 * library administration implements it.
 */
public interface FolderDocumentDeleter {

  void deleteDocument(UUID libraryId, UUID documentId, CurrentUser caller);
}
