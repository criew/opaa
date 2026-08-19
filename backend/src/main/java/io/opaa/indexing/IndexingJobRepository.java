package io.opaa.indexing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndexingJobRepository extends JpaRepository<IndexingJob, UUID> {

  /**
   * The most recent run for {@code libraryId}, or empty if it never ran (#478: concurrency and
   * status are per library, not global - see {@link IndexingJobService}).
   */
  Optional<IndexingJob> findTopByLibraryIdOrderByStartedAtDesc(UUID libraryId);

  /**
   * Whether a run for {@code libraryId} is currently {@link JobStatus#RUNNING} (#478): runs of
   * different libraries never block each other any more, so this is scoped to one library rather
   * than the whole {@code indexing_jobs} table.
   */
  boolean existsByStatusAndLibraryId(JobStatus status, UUID libraryId);
}
