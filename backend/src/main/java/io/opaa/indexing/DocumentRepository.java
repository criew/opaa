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
   * The parent-level counterpart of {@link #countByLibraryId}: top-level documents only, matching
   * what the document list pages over - shown as a library's {@code documentCount}. {@link
   * #countByLibraryId} keeps backing the delete guard, which must see every row.
   */
  long countByLibraryIdAndParentDocumentIdIsNull(UUID libraryId);

  /**
   * The paged search behind {@code KnowledgeLibraryService#listDocuments} with {@code q} (ADR-0022,
   * Entscheidung 5): top-level documents only ({@code parentDocumentId IS NULL}), matching either
   * their own file name or - via {@code attachmentRootIds}, resolved by the caller from matching
   * attachment rows - an attachment anywhere in their subtree. {@code escapedQ} must have {@code
   * \}, {@code %} and {@code _} backslash-escaped by the caller (see {@code
   * KnowledgeLibraryService#escapeLike}); the hand-written LIKE has no automatic escaping the way
   * the derived {@code ...ContainingIgnoreCase} finders do.
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
   * The paged Pflege-Anker list: every indexed document row of the library - top-level and
   * attachment alike - that has no row for {@code fieldKey}, optionally narrowed by {@code
   * escapedQ} (empty matches every name). Deliberately not the top-level paging of {@link
   * #searchTopLevelByFileNameOrAttachmentRoot}: this list must hold exactly the rows the anchor
   * counts, so that every entry is genuinely open and a Sammelzuweisung over the whole page cannot
   * overwrite a maintained value. Only {@code status} rows count - an anchor over documents that
   * were never indexed would never reach zero. The gap is a correlated {@code NOT EXISTS}, so no
   * unbounded id list travels between two queries.
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
   * document in every one of its descendant folders, one query for the whole set rather than one
   * per subfolder. Matches {@code LibraryFolderService#countDocumentsRecursive}'s semantics (the
   * count {@code LibraryFolderResponse.documentCount} shows before a recursive DELETE, ADR-0020
   * Entscheidung 5), not a shallow "direct children only" count.
   *
   * <p>A single recursive CTE, not application code walking per subfolder: {@code folder_tree}
   * expands every requested id in {@code folderIds} into itself plus its full descendant subtree,
   * tagging each descendant with the requested ancestor it came from ({@code root_id}); the outer
   * query then counts {@code documents} joined on every id in that expanded tree, grouped by {@code
   * root_id}. A {@code LEFT JOIN}, not an inner join, so a folder with zero documents anywhere in
   * its subtree still contributes a row with count {@code 0} rather than disappearing from the
   * result, unlike {@link #countByLibraryIdIn}'s inner-join equivalent.
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
   * Backs the upload endpoint's per-library deduplication: the same checksum is rejected a second
   * time within the same library, but is deliberately allowed in a different one - see the
   * acceptance criteria on {@code io.opaa.library.LibraryDocumentService#uploadDocument}. Scoped to
   * parentless rows, matching {@code uk_documents_library_checksum}'s own scope since migration
   * 017: an attachment row of an uploaded mail (ADR-0022) is derived content, not a user upload -
   * uploading a file whose bytes happen to equal an indexed attachment is not a duplicate.
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
   * Backs {@code LibraryStorageQuotaService}: the total bytes a library's documents currently
   * occupy, one aggregate query rather than loading every {@link Document} row to sum {@link
   * Document#getFileSize} in application code. Two {@code coalesce} layers:
   *
   * <ul>
   *   <li>the outer one maps the {@code SUM} of an empty result set to {@code 0} instead of {@code
   *       null} - callers would otherwise have to null-check a supposedly primitive byte count;
   *   <li>the inner one makes explicit, rather than leaving to {@code SUM}'s own null-skipping
   *       behaviour, that an individual row with no recorded {@code file_size} (the column is
   *       nullable) counts as {@code 0} - see {@code LibraryStorageQuotaService#usedBytes}'s own
   *       Javadoc.
   * </ul>
   */
  @Query(
      "select coalesce(sum(coalesce(d.fileSize, 0)), 0) from Document d where d.libraryId ="
          + " :libraryId")
  long sumFileSizeByLibraryId(@Param("libraryId") UUID libraryId);

  /**
   * Conditionally transitions an asynchronously-processed upload to {@code FAILED}. {@code
   * FileProcessingService#processUploadedFileAsync} re-reads the row by id before it starts, but
   * Tika parsing/embedding can run for seconds - long enough for {@code
   * LibraryDocumentService#deleteDocument} to remove that same row from another request in the
   * meantime. A plain {@code documentRepository.save} on the (now stale) entity would not notice:
   * {@link Document} assigns its own id in its constructor and carries no {@code @Version}, so
   * Hibernate's merge would silently re-{@code INSERT} it as a zombie row. A conditional {@code
   * UPDATE} has no such failure mode: it either affects the row that is still there, or nothing.
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
   * threshold} is stuck for good, not merely queued - the process that would have finished it (via
   * {@code uploadTaskExecutor}) died before {@link #markIndexed}/{@link #markFailed} could run, and
   * a fresh JVM start has no in-memory record of that task to wait for. A bulk {@code UPDATE}, not
   * a load-then-save loop, for the same reason as {@link #deleteByLibraryId}.
   *
   * <p>Scoped to {@code sourceType = UPLOAD} deliberately: a connector run ({@code FILESYSTEM}/
   * {@code HTTP_DIRECTORY}/{@code RSS_FEED}) also passes through a transient {@code PENDING} row,
   * and a crash mid-run could in principle leave one stuck the same way - but that failure mode
   * belongs to {@code indexing_jobs}' own {@code RUNNING} recovery, not to this upload-specific
   * runner and its upload-specific error message.
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
   * The connector counterpart to {@link #markIndexed(UUID, int, Instant)}, generalized for {@code
   * FileProcessingService#processFile}/{@code #processUrlFile}/{@code #processRssEntry}. Those
   * three paths only learn the checksum - and for URL/RSS sources, the remote's own {@code
   * last_modified}/publish marker - once chunking and embedding have already succeeded, unlike the
   * upload path, which persists its checksum on the {@code PENDING} row before this transition ever
   * runs. {@code lastModifiedRemote} is {@code null} for {@code processFile}'s filesystem
   * documents.
   *
   * <p>Same zero-rows-means-the-row-is-gone contract as {@link #markIndexed(UUID, int, Instant)}.
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
   * markers a run consults. Used by {@code PipelineReindexService} for documents whose source can
   * only be re-read by that run.
   *
   * <p>Both are required, and clearing only the checksum would be a no-op for the remote paths:
   * {@code UrlIndexingExecutor#isUnchanged}/{@code RssFeedIndexingExecutor#isUnchanged} decide
   * <em>before</em> downloading, from {@code last_modified_remote} plus {@link
   * DocumentStatus#INDEXED} alone - the checksum is never compared, because the bytes it would be
   * computed from have deliberately not been fetched yet. A {@code null} {@code
   * last_modified_remote} can never equal the remote's own value, so the download happens; the
   * {@code null} checksum then stops {@link FileProcessingService#processUrlFile} from skipping the
   * freshly downloaded file as unchanged in the second gate.
   *
   * @return the number of rows updated - {@code 0} means the row was deleted meanwhile, which needs
   *     no further action here
   */
  @Modifying
  @Transactional
  @Query("update Document d set d.checksum = null, d.lastModifiedRemote = null where d.id = :id")
  int markForReindexOnNextRun(@Param("id") UUID id);
}
