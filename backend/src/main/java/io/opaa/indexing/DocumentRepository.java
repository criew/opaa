package io.opaa.indexing;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

  Optional<Document> findByFilePath(String filePath);

  /**
   * Whether at least one attachment document for {@code sourceEntryUrl} (an RSS entry's own {@code
   * file_path}) already exists (#468, PR #492 review finding 1). Backs the "an entry indexed before
   * attachments existed must still get them backfilled" check in {@link
   * RssFeedIndexingExecutor#isUnchanged} 's caller - without it, an entry whose {@code pubDate}
   * never changes again would never have its attachments discovered, even once the feature to find
   * them exists.
   */
  boolean existsBySourceEntryUrl(String sourceEntryUrl);

  List<Document> findByLibraryId(UUID libraryId);

  long countByLibraryId(UUID libraryId);

  /**
   * Backs {@code KnowledgeLibraryService#listDocuments}'s paging and optional stichwort search
   * (#517): a page of a library's documents, plus the total across all pages the caller's paging
   * controls need. {@code q} is matched as a case-insensitive substring of the file name when
   * present, or ignored entirely (all of the library's documents) when {@code null} or blank -
   * {@link #findByLibraryId(UUID, Pageable)} below backs that second case, so an empty search field
   * never has to pass a "match everything" wildcard through to the database.
   */
  Page<Document> findByLibraryIdAndFileNameContainingIgnoreCase(
      UUID libraryId, String fileNameQuery, Pageable pageable);

  Page<Document> findByLibraryId(UUID libraryId, Pageable pageable);

  /**
   * Backs {@code KnowledgeLibraryService#deleteLibrary}'s connector-library path (#479, ADR-0018
   * Entscheidung 5): deleting a lauf-basierte (connector) library takes its whole document bestand
   * with it, unlike {@code UPLOAD}, which keeps the pre-existing "blocked while non-empty" guard.
   * Explicit {@code @Modifying} bulk {@code DELETE}, not a derived {@code deleteBy...} method -
   * Spring Data JPA executes those by loading every matching entity and removing it one by one,
   * which would cost one round trip per document instead of one for the whole bestand. Returns the
   * number of rows removed.
   */
  @Modifying
  @Query("delete from Document d where d.libraryId = :libraryId")
  long deleteByLibraryId(@Param("libraryId") UUID libraryId);

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

  /**
   * Conditionally transitions an asynchronously-processed upload to {@code FAILED} (PR #589 review,
   * finding 1). {@code FileProcessingService#processUploadedFileAsync} re-reads the row by id
   * before it starts, but Tika parsing/embedding can run for seconds - long enough for {@code
   * LibraryDocumentService#deleteDocument} to remove that same row from another request in the
   * meantime. A plain {@code documentRepository.save} on the (now stale) entity would not notice:
   * {@link Document} assigns its own id in its constructor and carries no {@code @Version}, so
   * Hibernate's merge would silently re-{@code INSERT} it as a zombie row - {@code FAILED} with no
   * backing file, or worse, {@code INDEXED} pointing at chunks the caller believes were already
   * cleaned up. A conditional {@code UPDATE} has no such failure mode: it either affects the row
   * that is still there, or affects nothing at all.
   *
   * @return the number of rows updated - {@code 0} means the row no longer exists, and the caller
   *     must clean up any chunks it already wrote instead of trying to persist anything
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.status = io.opaa.indexing.DocumentStatus.FAILED, d.errorMessage ="
          + " :errorMessage where d.id = :id")
  int markFailed(@Param("id") UUID id, @Param("errorMessage") String errorMessage);

  /**
   * The successful counterpart to {@link #markFailed} - same reasoning, same
   * zero-rows-means-the-row-is-gone contract.
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.status = io.opaa.indexing.DocumentStatus.INDEXED, d.chunkCount ="
          + " :chunkCount, d.indexedAt = :indexedAt, d.errorMessage = null where d.id = :id")
  int markIndexed(
      @Param("id") UUID id,
      @Param("chunkCount") int chunkCount,
      @Param("indexedAt") Instant indexedAt);
}
