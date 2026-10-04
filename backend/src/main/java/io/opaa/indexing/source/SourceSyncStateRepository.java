package io.opaa.indexing.source;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SourceSyncStateRepository extends JpaRepository<SourceSyncState, UUID> {

  Optional<SourceSyncState> findByLibraryId(UUID libraryId);

  /** Locks the row of the state {@code id} for the caller's transaction; empty when it is gone. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s.id from SourceSyncState s where s.id = :id")
  Optional<UUID> lockById(@Param("id") UUID id);

  /**
   * Called by {@code KnowledgeLibraryService} when a library's address or selection changes:
   * without a state the next run is a full one from scratch, which is what a changed selection
   * needs (ADR-0023, Entscheidung 4; ADR-0027, Entscheidung 3). The ON DELETE CASCADE only covers
   * the library's deletion.
   */
  long deleteByLibraryId(UUID libraryId);

  /**
   * Notes that a document of {@code containerKey} at {@code hierarchyPath} was deleted outside a
   * run, in the caller's transaction - only for a library with a state, else nothing is written.
   *
   * @return the rows written, {@code 0} or {@code 1}
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO source_sync_revisits"
              + " (id, sync_state_id, container_key, hierarchy_path, created_at)"
              + " SELECT :id, s.id, :containerKey, :hierarchyPath, :createdAt"
              + " FROM source_sync_state s WHERE s.library_id = :libraryId",
      nativeQuery = true)
  int recordRevisit(
      @Param("id") UUID id,
      @Param("libraryId") UUID libraryId,
      @Param("containerKey") String containerKey,
      @Param("hierarchyPath") String hierarchyPath,
      @Param("createdAt") Instant createdAt);

  /** Every revisit noted for the state {@code syncStateId}; read only through the scan journal. */
  @Query(
      value =
          "SELECT id AS \"id\", container_key AS \"containerKey\","
              + " hierarchy_path AS \"hierarchyPath\""
              + " FROM source_sync_revisits WHERE sync_state_id = :syncStateId",
      nativeQuery = true)
  List<Revisit> findRevisits(@Param("syncStateId") UUID syncStateId);

  /** Drops the revisits {@code ids}, once a full sync listed their folders. */
  @Modifying
  @Transactional
  @Query(value = "DELETE FROM source_sync_revisits WHERE id IN (:ids)", nativeQuery = true)
  int deleteRevisits(@Param("ids") Collection<UUID> ids);

  /**
   * Notes the documents of {@code libraryId} at {@code filePaths} as seen in the round {@code
   * scanId}, in the caller's transaction; a path without a document is skipped.
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO source_sync_presence (document_id, sync_state_id, scan_id)"
              + " SELECT d.id, :syncStateId, :scanId FROM documents d"
              + " WHERE d.library_id = :libraryId AND d.file_path IN (:filePaths)"
              + " ON CONFLICT (document_id) DO UPDATE"
              + " SET sync_state_id = EXCLUDED.sync_state_id, scan_id = EXCLUDED.scan_id",
      nativeQuery = true)
  int recordPresence(
      @Param("syncStateId") UUID syncStateId,
      @Param("scanId") UUID scanId,
      @Param("libraryId") UUID libraryId,
      @Param("filePaths") Collection<String> filePaths);

  /** The {@code file_path} of every document the round {@code scanId} has seen. */
  @Query(
      value =
          "SELECT d.file_path FROM source_sync_presence p JOIN documents d ON d.id = p.document_id"
              + " WHERE p.sync_state_id = :syncStateId AND p.scan_id = :scanId",
      nativeQuery = true)
  List<String> findPresentPaths(
      @Param("syncStateId") UUID syncStateId, @Param("scanId") UUID scanId);

  /**
   * Container key and hierarchy path of every top-level document of {@code libraryId} in a
   * container that the round {@code scanId} has not seen.
   */
  @Query(
      value =
          "SELECT DISTINCT d.source_container_key AS \"containerKey\","
              + " d.source_hierarchy_path AS \"hierarchyPath\""
              + " FROM documents d WHERE d.library_id = :libraryId"
              + " AND d.parent_document_id IS NULL AND d.source_container_key IS NOT NULL"
              + " AND NOT EXISTS (SELECT 1 FROM source_sync_presence p WHERE p.document_id = d.id"
              + " AND p.sync_state_id = :syncStateId AND p.scan_id = :scanId)",
      nativeQuery = true)
  List<Place> findUnseen(
      @Param("libraryId") UUID libraryId,
      @Param("syncStateId") UUID syncStateId,
      @Param("scanId") UUID scanId);

  /** Drops the presence of every round of the state {@code syncStateId}. */
  @Modifying
  @Transactional
  @Query(
      value = "DELETE FROM source_sync_presence WHERE sync_state_id = :syncStateId",
      nativeQuery = true)
  int clearPresence(@Param("syncStateId") UUID syncStateId);

  /** Where a document stands in its source. */
  interface Place {
    String getContainerKey();

    String getHierarchyPath();
  }

  /** A document deleted outside a run: its folder is listed by the next full sync. */
  interface Revisit {
    UUID getId();

    String getContainerKey();

    String getHierarchyPath();
  }
}
