package io.opaa.library;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryFolderRepository extends JpaRepository<LibraryFolder, UUID> {

  /**
   * The direct subfolders of {@code parentFolderId} - always called with a non-null id ({@link
   * LibraryFolderService#deleteFolder}'s recursive descent starts at an already-loaded folder), so
   * this deliberately does not also cover the library's root the way {@code
   * findByLibraryIdAndParentFolderIdIsNull} below does; there is no #820 use case yet that needs
   * both in one call.
   */
  List<LibraryFolder> findByLibraryIdAndParentFolderId(UUID libraryId, UUID parentFolderId);

  /**
   * Backs the root-level half of the create/rename conflict check (#820 acceptance criteria:
   * "Doppelte Namen im selben Parent liefern 409") - exact, case-sensitive match, mirroring the
   * case-sensitive {@code uk_library_folders_root_name} partial unique index (migration 062) this
   * is a pre-flight for. Split from {@link #findByLibraryIdAndParentFolderIdAndName(UUID, UUID,
   * String)} rather than accepting a nullable {@code parentFolderId} in one method, the same
   * explicit-{@code IsNull}-suffix convention {@code AssetGrantHistoryRepository}/{@code
   * LibraryVisibilityHistoryRepository} already use for their own nullable columns, instead of
   * relying on a derived query's implicit null-parameter handling.
   */
  Optional<LibraryFolder> findByLibraryIdAndParentFolderIdIsNullAndName(
      UUID libraryId, String name);

  /** The non-root counterpart of {@link #findByLibraryIdAndParentFolderIdIsNullAndName}. */
  Optional<LibraryFolder> findByLibraryIdAndParentFolderIdAndName(
      UUID libraryId, UUID parentFolderId, String name);

  /**
   * Excludes {@code excludedFolderId} from the root-level conflict check - used by {@link
   * LibraryFolderService#renameFolder}, where the folder being renamed is itself allowed to keep
   * its current name (a no-op rename) without tripping over its own row.
   */
  Optional<LibraryFolder> findByLibraryIdAndParentFolderIdIsNullAndNameAndIdNot(
      UUID libraryId, String name, UUID excludedFolderId);

  /** The non-root counterpart of {@link #findByLibraryIdAndParentFolderIdIsNullAndNameAndIdNot}. */
  Optional<LibraryFolder> findByLibraryIdAndParentFolderIdAndNameAndIdNot(
      UUID libraryId, UUID parentFolderId, String name, UUID excludedFolderId);
}
