package io.opaa.indexing.source.confluence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfluenceSyncStateRepository extends JpaRepository<ConfluenceSyncState, UUID> {

  Optional<ConfluenceSyncState> findByLibraryId(UUID libraryId);

  // KnowledgeLibraryService#updateLibrary calls this when a CONFLUENCE library's address or space
  // selection changes: without a state the next run is a full one, which is exactly what a
  // changed selection needs (ADR-0023, Entscheidung 4). The ON DELETE CASCADE only covers the
  // library's deletion.
  long deleteByLibraryId(UUID libraryId);
}
