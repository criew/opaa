package io.opaa.library;

import io.opaa.permission.AssetOwnershipDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The knowledge-library answer to {@link AssetOwnershipDirectory}: {@code
 * fk_knowledge_libraries_owner_group_organization} is RESTRICT, so a group that still owns a
 * library must be refused before the delete reaches the database. The one place {@code
 * io.opaa.group} learns that libraries exist at all - through the port, not through this package.
 */
@Component
class LibraryAssetOwnershipDirectory implements AssetOwnershipDirectory {

  private final KnowledgeLibraryRepository libraryRepository;

  LibraryAssetOwnershipDirectory(KnowledgeLibraryRepository libraryRepository) {
    this.libraryRepository = libraryRepository;
  }

  @Override
  public boolean existsAssetOwnedByGroup(UUID groupId) {
    return libraryRepository.existsByOwnerGroupId(groupId);
  }

  @Override
  public String ownedAssetConflictMessage() {
    return "Die Gruppe besitzt noch Bibliotheken und kann nicht gelöscht werden";
  }
}
