package io.opaa.indexing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * Fails every currently {@link JobStatus#RUNNING} row, unconditionally (#501). Called once, right
   * after application startup: a fresh JVM cannot possibly still be running the {@code @Async} task
   * a {@code RUNNING} row refers to - that task lived in the previous process, which either
   * discarded it (the {@code DiscardPolicy} this issue also removes) or died with it. Leaving such
   * a row {@code RUNNING} forever is exactly what locks its library out of every future trigger
   * (partial unique index from migration 028, {@code IndexingJobService#isJobRunning}) with no way
   * to resolve it from the UI - see {@code IndexingJobRecoveryScheduler#recoverOnStartup}.
   *
   * <p>A bulk {@code UPDATE}, not load-then-save: this can affect every library's stuck row in one
   * statement, mirroring {@code DocumentRepository#markFailed}'s reasoning for the same shape of
   * problem.
   *
   * @return the number of rows recovered, purely for logging - the caller does not otherwise act on
   *     it
   */
  @Modifying
  @Transactional
  @Query(
      "update IndexingJob j set j.status = io.opaa.indexing.JobStatus.FAILED, j.errorMessage ="
          + " :errorMessage, j.completedAt = :completedAt where j.status ="
          + " io.opaa.indexing.JobStatus.RUNNING")
  int failAllRunningJobs(
      @Param("errorMessage") String errorMessage, @Param("completedAt") Instant completedAt);

  /**
   * Fails every {@link JobStatus#RUNNING} row whose {@link IndexingJob#getLastProgressAt()}
   * heartbeat is older than {@code cutoff} (#501). Unlike {@link #failAllRunningJobs}, this runs
   * periodically while the application keeps running, not only once at startup - it is the only
   * guard against a run that is orphaned *without* a restart: a task silently dropped by a full
   * queue before this issue's {@code AbortPolicy} change took effect, or one that hangs
   * indefinitely (a slow/unreachable remote source, say).
   *
   * <p><b>#501 review, finding 1.</b> Filters on {@code lastProgressAt}, not {@code startedAt}: a
   * genuinely active run of a large bestand can easily exceed {@code
   * IndexingProperties#staleJobTimeout()} in wall-clock age alone, and {@code updateProgress}
   * touches {@code lastProgressAt} on every file/entry it processes - so this only ever catches a
   * run that has actually stopped making progress, never one that is merely long-running.
   *
   * @return the number of rows recovered, purely for logging - the caller does not otherwise act on
   *     it
   */
  @Modifying
  @Transactional
  @Query(
      "update IndexingJob j set j.status = io.opaa.indexing.JobStatus.FAILED, j.errorMessage ="
          + " :errorMessage, j.completedAt = :completedAt where j.status ="
          + " io.opaa.indexing.JobStatus.RUNNING and j.lastProgressAt < :cutoff")
  int failStaleRunningJobs(
      @Param("errorMessage") String errorMessage,
      @Param("cutoff") Instant cutoff,
      @Param("completedAt") Instant completedAt);

  /**
   * Completes {@code id} only if it is still {@link JobStatus#RUNNING} (#501 review, finding 1).
   * Without this guard, a job the stale-run sweep or startup recovery already failed - while its
   * executor thread, unaware, kept running - would have that thread's eventual {@code completeJob}
   * silently flip the row back from {@code FAILED} to {@code COMPLETED}, leaving the already-set
   * {@code errorMessage} stranded on an otherwise-successful-looking row. Mirrors {@code
   * DocumentRepository#markIndexed}'s same conditional-update shape and reasoning.
   *
   * @return the number of rows updated - 0 means the row was not {@code RUNNING} any more (already
   *     recovered) or does not exist; the caller distinguishes the two via {@link #existsById}
   */
  @Modifying
  @Transactional
  @Query(
      "update IndexingJob j set j.status = io.opaa.indexing.JobStatus.COMPLETED,"
          + " j.documentsProcessed = :documentsProcessed, j.documentsFailed = :documentsFailed,"
          + " j.documentsSkipped = :documentsSkipped, j.documentsIndexedTotal ="
          + " :documentsIndexedTotal, j.completedAt = :completedAt where j.id = :id and j.status ="
          + " io.opaa.indexing.JobStatus.RUNNING")
  int completeIfRunning(
      @Param("id") UUID id,
      @Param("documentsProcessed") int documentsProcessed,
      @Param("documentsFailed") int documentsFailed,
      @Param("documentsSkipped") int documentsSkipped,
      @Param("documentsIndexedTotal") int documentsIndexedTotal,
      @Param("completedAt") Instant completedAt);

  /**
   * Fails {@code id} only if it is still {@link JobStatus#RUNNING} (#501 review, finding 1) - the
   * same reasoning and shape as {@link #completeIfRunning}, for the failure path.
   *
   * @return the number of rows updated - 0 means the row was not {@code RUNNING} any more or does
   *     not exist; the caller distinguishes the two via {@link #existsById}
   */
  @Modifying
  @Transactional
  @Query(
      "update IndexingJob j set j.status = io.opaa.indexing.JobStatus.FAILED, j.errorMessage ="
          + " :errorMessage, j.completedAt = :completedAt where j.id = :id and j.status ="
          + " io.opaa.indexing.JobStatus.RUNNING")
  int failIfRunning(
      @Param("id") UUID id,
      @Param("errorMessage") String errorMessage,
      @Param("completedAt") Instant completedAt);
}
