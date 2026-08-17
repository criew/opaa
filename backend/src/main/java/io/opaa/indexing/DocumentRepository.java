package io.opaa.indexing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

  Optional<Document> findByFilePath(String filePath);

  List<Document> findByLibraryId(UUID libraryId);

  long countByLibraryId(UUID libraryId);

  /**
   * Backs the upload endpoint's per-library deduplication (#420): the same checksum is rejected a
   * second time within the same library, but is deliberately allowed in a different one - see the
   * acceptance criteria on {@code io.opaa.library.LibraryDocumentService#uploadDocument}.
   */
  Optional<Document> findByLibraryIdAndChecksum(UUID libraryId, String checksum);
}
