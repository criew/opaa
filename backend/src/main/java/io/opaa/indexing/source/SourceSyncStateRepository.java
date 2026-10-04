package io.opaa.indexing.source;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SourceSyncStateRepository extends JpaRepository<SourceSyncState, UUID> {

  Optional<SourceSyncState> findByLibraryId(UUID libraryId);

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

  /** Every revisit noted for the state {@code syncStateId}. */
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

  /** A document deleted outside a run: its folder is listed by the next full sync. */
  interface Revisit {
    UUID getId();

    String getContainerKey();

    String getHierarchyPath();
  }
}
