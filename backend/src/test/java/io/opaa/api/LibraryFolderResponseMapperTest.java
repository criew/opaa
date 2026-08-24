package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderDetail;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against a directly constructed entity - the mapper
 * counterpart of {@code SpaceResponseMapperTest} (#860): pins {@link LibraryFolderResponseMapper}'s
 * field-by-field behaviour.
 */
class LibraryFolderResponseMapperTest {

  @Test
  void toResponseCopiesEveryFolderFieldAndTheRecursiveDocumentCount() {
    UUID libraryId = UUID.randomUUID();
    UUID parentFolderId = UUID.randomUUID();
    LibraryFolder folder =
        new LibraryFolder(libraryId, parentFolderId, "Protokolle", UUID.randomUUID());
    LibraryFolderDetail detail = new LibraryFolderDetail(folder, 7L);

    LibraryFolderResponse response = LibraryFolderResponseMapper.toResponse(detail);

    assertThat(response.getId()).isEqualTo(folder.getId());
    assertThat(response.getLibraryId()).isEqualTo(libraryId);
    assertThat(response.getName()).isEqualTo("Protokolle");
    assertThat(response.getDocumentCount()).isEqualTo(7L);
    assertThat(response.getCreatedAt()).isEqualTo(folder.getCreatedAt());
    assertThat(response.getParentFolderId()).isEqualTo(parentFolderId);
  }

  @Test
  void toResponseLeavesParentFolderIdNullForARootLevelFolder() {
    LibraryFolder folder = new LibraryFolder(UUID.randomUUID(), null, "Archiv", UUID.randomUUID());
    LibraryFolderDetail detail = new LibraryFolderDetail(folder, 0L);

    assertThat(LibraryFolderResponseMapper.toResponse(detail).getParentFolderId()).isNull();
  }
}
