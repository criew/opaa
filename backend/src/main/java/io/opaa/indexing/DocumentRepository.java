package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
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

  /**
   * Document identity is scoped to {@code (library_id, file_path)}, enforced by {@code
   * uk_documents_library_path} (migration 067): the same path or URL indexed into two different
   * libraries is two independent documents, never a "move" of one into the other. Backs every
   * dedup/change-detection lookup in {@link FileProcessingService}, {@code UrlIndexingExecutor} and
   * {@code RssFeedIndexingExecutor}.
   */
  Optional<Document> findByLibraryIdAndFilePath(UUID libraryId, String filePath);

  /**
   * Whether at least one attachment document for {@code sourceEntryUrl} (an RSS entry's own {@code
   * file_path}) already exists in {@code libraryId}. Backs the "an entry indexed before attachments
   * existed must still get them backfilled" check in {@code RssFeedIndexingExecutor#isUnchanged}'s
   * caller. Scoped to {@code libraryId} - another library's attachments for the same entry URL must
   * never suppress this library's own backfill.
   */
  boolean existsBySourceEntryUrlAndLibraryId(String sourceEntryUrl, UUID libraryId);

  /**
   * Moves a connector document's title and source context without touching its chunks - a
   * Confluence page renamed or moved without a body change (ADR-0023).
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.fileName = :fileName, d.sourceContainerKey = :containerKey,"
          + " d.sourceHierarchyPath = :hierarchyPath where d.id = :id")
  int refreshConnectorTitleAndContext(
      @Param("id") UUID id,
      @Param("fileName") String fileName,
      @Param("containerKey") String containerKey,
      @Param("hierarchyPath") String hierarchyPath);

  /**
   * Every attachment of {@code parentDocumentId} (ADR-0022, Entscheidung 4) - the FK-backed
   * generalization of {@link #existsBySourceEntryUrlAndLibraryId}'s RSS-only path lookup.
   */
  List<Document> findByParentDocumentId(UUID parentDocumentId);

  List<Document> findByLibraryId(UUID libraryId);

  /**
   * Backs {@link LowChunkDocumentAuditService#findLowChunkDocuments}: one organization's {@link
   * DocumentStatus#INDEXED} documents at or below {@code chunkCountThreshold} chunks, paged. Backed
   * by the partial index {@code idx_documents_indexed_chunk_count} (migration 002).
   */
  Page<Document> findByOrganizationIdAndStatusAndChunkCountLessThanEqual(
      UUID organizationId, DocumentStatus status, int chunkCountThreshold, Pageable pageable);

  /**
   * Backs {@link StaleDocumentCleanupService#cleanupVanished}: every document of a single {@code
   * (libraryId, sourceType)} pair, the candidate set a completed connector run checks against its
   * own freshly discovered {@code filePath}s to find what vanished from the source.
   */
  List<Document> findByLibraryIdAndSourceType(UUID libraryId, DocumentSourceType sourceType);

  /**
   * Backs {@code io.opaa.library.LibraryFolderService#deleteFolder}: every document directly inside
   * a folder, cleaned up one by one through {@code LibraryDocumentService#deleteDocument} (chunks,
   * stored file, row) rather than a bulk delete or a database cascade - see {@code
   * documents.folder_id}'s {@code onDelete: RESTRICT} and ADR-0020, Entscheidung 5.
   */
  List<Document> findByFolderId(UUID folderId);

  /**
   * The count-only counterpart to {@link #findByFolderId} - backs {@code LibraryFolderService}'s
   * {@code documentCount}, which a confirmation dialog shows before a recursive DELETE; cheaper
   * than loading every row just to call {@code .size()}.
   */
  long countByFolderId(UUID folderId);

  long countByLibraryId(UUID libraryId);

  /**
   * The bestand the Pflege-Anker and the Füllgrad measure against: a library's {@code INDEXED}
   * documents, attachments included - a metadata value hangs at every document row, not only at the
   * ones the document list pages over.
   */
  long countByLibraryIdAndStatus(UUID libraryId, DocumentStatus status);

  /** The indexed bestand of a whole search scope - the base of the filter Füllstand. */
  long countByLibraryIdInAndStatus(Collection<UUID> libraryIds, DocumentStatus status);

  /**
   * The same bestand broken down per library - the base of the one Füllstand count. A library
   * without documents in {@code status} is absent from the result.
   */
  @Query(
      "select d.libraryId as libraryId, count(d) as documentCount from Document d"
          + " where d.libraryId in :libraryIds and d.status = :status group by d.libraryId")
  List<LibraryDocumentCount> countByLibraryAndStatus(
      @Param("libraryIds") Collection<UUID> libraryIds, @Param("status") DocumentStatus status);

  /**
   * The parent-level counterpart of {@link #countByLibraryId}: top-level documents only, matching
   * what the document list pages over - shown as a library's {@code documentCount}. {@link
   * #countByLibraryId} keeps backing the delete guard, which must see every row.
   */
  long countByLibraryIdAndParentDocumentIdIsNull(UUID libraryId);

  /**
   * The paged search behind {@code KnowledgeLibraryService#listDocuments} with {@code q} (ADR-0022,
   * Entscheidung 5): top-level documents only, matching their own file name or - via {@code
   * attachmentRootIds}, resolved by the caller - an attachment anywhere in their subtree. {@code
   * escapedQ} must be backslash-escaped by the caller; this hand-written LIKE has no escaping of
   * its own.
   */
  @Query(
      """
      SELECT d FROM Document d
      WHERE d.libraryId = :libraryId AND d.parentDocumentId IS NULL
        AND (LOWER(d.fileName) LIKE LOWER(CONCAT('%', :escapedQ, '%')) ESCAPE '\\'
             OR d.id IN :attachmentRootIds)
      """)
  Page<Document> searchTopLevelByFileNameOrAttachmentRoot(
      @Param("libraryId") UUID libraryId,
      @Param("escapedQ") String escapedQ,
      @Param("attachmentRootIds") Collection<UUID> attachmentRootIds,
      Pageable pageable);

  /**
   * Every attachment row of {@code libraryId} whose file name matches {@code q} case-insensitively
   * - the first step of the attachment-aware search above: the caller walks each hit up its {@code
   * parentDocumentId} chain to the top-level root before paging. Derived query, so LIKE
   * metacharacters in {@code q} are escaped automatically. A slim projection, not full entities -
   * only the two ids are needed, and the hit count is unbounded.
   */
  List<AttachmentParentRef>
      findByLibraryIdAndParentDocumentIdIsNotNullAndFileNameContainingIgnoreCase(
          UUID libraryId, String q);

  /**
   * The paged Pflege-Anker list: every indexed document row of the library, top-level and
   * attachment alike, that has no row for {@code fieldKey}. It must hold exactly the rows the
   * anchor counts, so a Sammelzuweisung over a page cannot overwrite a maintained value; only
   * {@code status} rows count, or the anchor would never reach zero. The gap is a correlated {@code
   * NOT EXISTS}, so no unbounded id list travels between two queries.
   */
  @Query(
      """
      SELECT d FROM Document d
      WHERE d.libraryId = :libraryId AND d.status = :status
        AND LOWER(d.fileName) LIKE LOWER(CONCAT('%', :escapedQ, '%')) ESCAPE '\\'
        AND NOT EXISTS (SELECT 1 FROM DocumentMetadataValue v
                        WHERE v.documentId = d.id AND v.fieldKey = :fieldKey)
      """)
  Page<Document> searchWithoutMetadataValue(
      @Param("libraryId") UUID libraryId,
      @Param("escapedQ") String escapedQ,
      @Param("fieldKey") String fieldKey,
      @Param("status") DocumentStatus status,
      Pageable pageable);

  /** The two ids the attachment-aware search prefilter needs - see the finder above. */
  interface AttachmentParentRef {
    UUID getId();

    UUID getParentDocumentId();
  }

  /**
   * The attachment rows of every parent in {@code parentDocumentIds}, ordered by {@code filePath}
   * (which embeds the extraction-order index for mail attachments, ADR-0022 Entscheidung 2) - backs
   * {@code KnowledgeLibraryService#listDocuments}'s per-page subtree expansion, called once per
   * nesting level, not once per parent.
   */
  List<Document> findByParentDocumentIdInOrderByFilePathAsc(Collection<UUID> parentDocumentIds);

  Page<Document> findByLibraryId(UUID libraryId, Pageable pageable);

  /**
   * Backs a folder-scoped {@code GET .../documents} (no {@code q}): a page of exactly the documents
   * sitting directly in {@code folderId}, mirroring {@link #findByLibraryId(UUID, Pageable)}'s
   * existing root/whole-library paging.
   */
  Page<Document> findByLibraryIdAndFolderId(UUID libraryId, UUID folderId, Pageable pageable);

  /**
   * The library root's counterpart to {@link #findByLibraryIdAndFolderId} - backs {@code GET
   * .../documents} with no {@code folderId} and no {@code q}, ADR-0020's convention that a {@code
   * null folder_id} means the library's root. Top-level documents only: an attachment ({@code
   * parentDocumentId} set) is never paged independently, it rides along with its parent (see {@link
   * #findByParentDocumentIdInOrderByFilePathAsc}).
   */
  Page<Document> findByLibraryIdAndFolderIdIsNullAndParentDocumentIdIsNull(
      UUID libraryId, Pageable pageable);

  /**
   * The recursive document counts of a set of folders - each folder's own documents plus every
   * document in its descendant folders, one query for the whole set. Matches {@code
   * LibraryFolderService#countDocumentsRecursive}'s semantics (ADR-0020, Entscheidung 5), not a
   * shallow count. A {@code LEFT JOIN}, so a folder with an empty subtree still yields a row with
   * count {@code 0} instead of disappearing.
   */
  @Query(
      value =
          "WITH RECURSIVE folder_tree AS ("
              + "  SELECT id AS root_id, id FROM library_folders WHERE id IN (:folderIds)"
              + "  UNION ALL"
              + "  SELECT ft.root_id, lf.id FROM library_folders lf"
              + "  JOIN folder_tree ft ON lf.parent_folder_id = ft.id"
              + ") "
              + "SELECT ft.root_id AS folder_id, count(d.id) AS document_count "
              + "FROM folder_tree ft LEFT JOIN documents d ON d.folder_id = ft.id "
              + "GROUP BY ft.root_id",
      nativeQuery = true)
  List<FolderDocumentCount> countRecursiveByFolderIdIn(
      @Param("folderIds") Collection<UUID> folderIds);

  interface FolderDocumentCount {
    UUID getFolderId();

    long getDocumentCount();
  }

  /**
   * Backs {@code KnowledgeLibraryService#deleteLibrary}'s connector-library path (ADR-0018
   * Entscheidung 5): deleting a lauf-basierte (connector) library takes its whole document bestand
   * with it, unlike {@code UPLOAD}, which keeps the pre-existing "blocked while non-empty" guard.
   * Explicit {@code @Modifying} bulk {@code DELETE}, not a derived {@code deleteBy...} method -
   * Spring Data JPA executes those by loading every matching entity and removing it one by one.
   */
  @Modifying
  @Query("delete from Document d where d.libraryId = :libraryId")
  long deleteByLibraryId(@Param("libraryId") UUID libraryId);

  /**
   * Backs the upload endpoint's per-library deduplication: the same checksum is rejected within one
   * library and deliberately allowed in another. Scoped to parentless rows, matching {@code
   * uk_documents_library_checksum} - an attachment row is derived content, so uploading a file
   * whose bytes equal an indexed attachment is not a duplicate.
   */
  Optional<Document> findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
      UUID libraryId, String checksum);

  /**
   * Backs {@code KnowledgeLibraryService#listLibraries}'s {@code documentCount} column: one grouped
   * query for the whole page instead of one count per row. Libraries with no documents simply have
   * no row here - the caller defaults those to zero. Top-level documents only, matching the
   * document list's own parent-level totalElements - attachments show up inside their parent's
   * group, not in this count.
   */
  @Query(
      "select d.libraryId as libraryId, count(d) as documentCount from Document d"
          + " where d.libraryId in :libraryIds and d.parentDocumentId is null"
          + " group by d.libraryId")
  List<LibraryDocumentCount> countTopLevelByLibraryIdIn(
      @Param("libraryIds") Collection<UUID> libraryIds);

  interface LibraryDocumentCount {
    UUID getLibraryId();

    long getDocumentCount();
  }

  /**
   * Backs {@code LibraryStorageQuotaService}: the total bytes a library's documents occupy, as one
   * aggregate query. The outer {@code coalesce} maps the {@code SUM} of an empty result to {@code
   * 0} rather than {@code null}, so callers need no null check; the inner one states explicitly
   * that a row without a recorded {@code file_size} counts as {@code 0}.
   */
  @Query(
      "select coalesce(sum(coalesce(d.fileSize, 0)), 0) from Document d where d.libraryId ="
          + " :libraryId")
  long sumFileSizeByLibraryId(@Param("libraryId") UUID libraryId);

  /**
   * Conditionally transitions an asynchronously-processed upload to {@code FAILED}. Parsing and
   * embedding can run for seconds, long enough for a concurrent delete to remove the row. A plain
   * save would re-insert it as a zombie, since {@link Document} assigns its own id and carries no
   * {@code @Version}; a conditional {@code UPDATE} either hits the row or nothing.
   *
   * @return the number of rows updated - {@code 0} means the row no longer exists, and the caller
   *     must clean up any chunks it already wrote instead of trying to persist anything
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.status = io.opaa.api.types.DocumentStatus.FAILED, d.errorMessage ="
          + " :errorMessage where d.id = :id")
  int markFailed(@Param("id") UUID id, @Param("errorMessage") String errorMessage);

  /**
   * Like {@link #markFailed}, plus {@code chunk_count = 0}: for a document whose chunks were just
   * removed because its new version is legitimately empty. {@link #markFailed} deliberately leaves
   * {@code chunk_count} alone, which is right for a document that kept its previous chunks and
   * wrong for one that has none left, so the column must say which of the two a {@code FAILED} row
   * is.
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.status = io.opaa.api.types.DocumentStatus.FAILED, d.errorMessage ="
          + " :errorMessage, d.chunkCount = 0 where d.id = :id")
  int markFailedWithoutChunks(@Param("id") UUID id, @Param("errorMessage") String errorMessage);

  /**
   * The successful counterpart to {@link #markFailed} - same reasoning, same
   * zero-rows-means-the-row-is-gone contract.
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.status = io.opaa.api.types.DocumentStatus.INDEXED, d.chunkCount ="
          + " :chunkCount, d.indexedAt = :indexedAt, d.errorMessage = null where d.id = :id")
  int markIndexed(
      @Param("id") UUID id,
      @Param("chunkCount") int chunkCount,
      @Param("indexedAt") Instant indexedAt);

  /**
   * Backs {@code UploadPendingRecoveryRunner}: every {@code PENDING} upload created before {@code
   * threshold} is stuck for good - the process that would have finished it died. A bulk {@code
   * UPDATE}, for the same reason as {@link #deleteByLibraryId}, scoped to {@code UPLOAD}: a
   * connector run's transient {@code PENDING} row belongs to {@code indexing_jobs}' own recovery.
   *
   * @return the number of rows transitioned to {@code FAILED}
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.status = io.opaa.api.types.DocumentStatus.FAILED, d.errorMessage ="
          + " :errorMessage where d.status = io.opaa.api.types.DocumentStatus.PENDING and"
          + " d.sourceType = io.opaa.api.types.DocumentSourceType.UPLOAD and d.createdAt <"
          + " :threshold")
  int failStalePending(
      @Param("errorMessage") String errorMessage, @Param("threshold") Instant threshold);

  /**
   * Records which core-metadata extraction version last ran over a document (ADR-0024). A targeted
   * {@code UPDATE} rather than an entity save: it runs from the ingest after the row's own insert
   * has committed, and must not resurrect a row a concurrent delete already removed - the same
   * zero-rows-means-the-row-is-gone contract as {@link #markIndexed(UUID, int, Instant)}.
   */
  @Modifying
  @Transactional
  @Query("update Document d set d.metadataExtractionVersion = :version where d.id = :id")
  int updateMetadataExtractionVersion(@Param("id") UUID id, @Param("version") int version);

  /**
   * Hands a document back to the Bestandslauf: a manually deleted core value must be
   * re-extractable, and the run selects only documents without a current extraction version.
   */
  @Modifying
  @Transactional
  @Query("update Document d set d.metadataExtractionVersion = null where d.id = :id")
  int clearMetadataExtractionVersion(@Param("id") UUID id);

  /**
   * Records the context-prefix version this document's chunks were last embedded under (#1072). A
   * targeted {@code UPDATE} for the same reason {@link #updateMetadataExtractionVersion} is one: it
   * must not resurrect a row a concurrent delete already removed.
   */
  @Modifying
  @Transactional
  @Query("update Document d set d.contextPrefixVersion = :version where d.id = :id")
  int updateContextPrefixVersion(@Param("id") UUID id, @Param("version") int version);

  /**
   * Hands a document to the Kontextpraefix-Nachlauf: a corrected prefix-effective value changes the
   * prefix of every chunk, and the run selects only documents without a current prefix version.
   */
  @Modifying
  @Transactional
  @Query("update Document d set d.contextPrefixVersion = null where d.id = :id")
  int clearContextPrefixVersion(@Param("id") UUID id);

  /**
   * The connector counterpart to {@link #markIndexed(UUID, int, Instant)}: those paths only learn
   * the checksum - and the remote's own change marker - once chunking and embedding have succeeded,
   * unlike the upload path, which persists its checksum on the {@code PENDING} row beforehand.
   * {@code lastModifiedRemote} is {@code null} for filesystem documents. Same
   * zero-rows-means-the-row-is-gone contract.
   */
  @Modifying
  @Transactional
  @Query(
      "update Document d set d.status = io.opaa.api.types.DocumentStatus.INDEXED, d.chunkCount ="
          + " :chunkCount, d.indexedAt = :indexedAt, d.checksum = :checksum, d.lastModifiedRemote ="
          + " :lastModifiedRemote, d.errorMessage = null where d.id = :id")
  int markIndexedFromSource(
      @Param("id") UUID id,
      @Param("chunkCount") int chunkCount,
      @Param("indexedAt") Instant indexedAt,
      @Param("checksum") String checksum,
      @Param("lastModifiedRemote") String lastModifiedRemote);

  /**
   * Marks a document for reprocessing by its own connector run, by clearing <b>both</b> change
   * markers a run consults. Clearing only the checksum would be a no-op for the remote paths, which
   * decide from {@code last_modified_remote} plus {@link DocumentStatus#INDEXED} before
   * downloading; the cleared checksum then stops the second gate from skipping the fresh download.
   *
   * @return the number of rows updated - {@code 0} means the row was deleted meanwhile, which needs
   *     no further action here
   */
  @Modifying
  @Transactional
  @Query("update Document d set d.checksum = null, d.lastModifiedRemote = null where d.id = :id")
  int markForReindexOnNextRun(@Param("id") UUID id);
}
