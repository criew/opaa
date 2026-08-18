package io.opaa.indexing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * Backs {@code KnowledgeLibraryService#listLibraries}'s {@code documentCount} column (#477): one
   * grouped query for the whole page instead of {@link #countByLibraryId} once per row, which would
   * cost one extra query per listed library. Libraries with no documents simply have no row here -
   * the caller defaults those to zero.
   */
  @Query(
      "select d.libraryId as libraryId, count(d) as documentCount from Document d"
          + " where d.libraryId in :libraryIds group by d.libraryId")
  List<LibraryDocumentCount> countByLibraryIdIn(@Param("libraryIds") Collection<UUID> libraryIds);

  interface LibraryDocumentCount {
    UUID getLibraryId();

    long getDocumentCount();
  }
}
