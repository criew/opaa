package io.opaa.indexing;

import java.util.List;
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

  /**
   * Every run for {@code libraryId}, newest first (#513) - used only by {@code
   * IndexingJobService#pruneOldRuns}, which must see every row beyond the retained last 10 to
   * delete them. Never used to answer the run-history endpoint - see {@link
   * #findTop10ByLibraryIdOrderByStartedAtDesc} for that (PR #604 review, finding 3): a
   * Bestandsbibliothek with hundreds of historical rows predating this issue's retention pruning
   * (older rows are only pruned going forward, on the next {@code startJob}) would otherwise load
   * every one of them, plus one {@code IndexingRunEventRepository} query per row.
   */
  List<IndexingJob> findByLibraryIdOrderByStartedAtDesc(UUID libraryId);

  /**
   * The last {@value IndexingJobService#MAX_RETAINED_RUNS_PER_LIBRARY} runs for {@code libraryId},
   * newest first (#513, PR #604 review finding 3) - bounded at the query itself rather than by
   * truncating an unbounded list in Java, so a library with far more historical rows than the
   * current retention limit never loads more of them than the endpoint actually returns.
   */
  List<IndexingJob> findTop10ByLibraryIdOrderByStartedAtDesc(UUID libraryId);
}
