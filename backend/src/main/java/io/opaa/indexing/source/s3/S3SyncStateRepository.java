package io.opaa.indexing.source.s3;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface S3SyncStateRepository extends JpaRepository<S3SyncState, UUID> {

  Optional<S3SyncState> findByLibraryId(UUID libraryId);

  /**
   * Called by {@code KnowledgeLibraryService} when an S3 library's endpoint or scopes change:
   * without a state the next run starts from scratch, which is what a changed selection needs
   * (ADR-0027, Entscheidung 3). The ON DELETE CASCADE only covers the library's deletion.
   */
  long deleteByLibraryId(UUID libraryId);
}
