package io.opaa.indexing.source;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceSyncStateRepository extends JpaRepository<SourceSyncState, UUID> {

  Optional<SourceSyncState> findByLibraryId(UUID libraryId);

  /**
   * Called by {@code KnowledgeLibraryService} when a library's address or selection changes:
   * without a state the next run is a full one from scratch, which is what a changed selection
   * needs (ADR-0023, Entscheidung 4; ADR-0027, Entscheidung 3). The ON DELETE CASCADE only covers
   * the library's deletion.
   */
  long deleteByLibraryId(UUID libraryId);
}
