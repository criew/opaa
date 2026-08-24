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
   * The most recent run for {@code libraryId} within {@code organizationId}, or empty if none
   * matches (concurrency and status are per library, not global - see {@link IndexingJobService}).
   * {@code organizationId} is a second, independent guard on top of {@code libraryId}: {@code
   * libraryId} alone cannot name a library from a different organization (the composite foreign key
   * forbids that at the database level), but this still requires the caller's own organization to
   * match, rather than relying solely on whatever authorized {@code libraryId} in the first place.
   */
  Optional<IndexingJob> findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
      UUID libraryId, UUID organizationId);

  /**
   * Whether a run for {@code libraryId} within {@code organizationId} is currently {@link
   * JobStatus#RUNNING}: runs of different libraries never block each other, so this is scoped to
   * one library rather than the whole {@code indexing_jobs} table. {@code organizationId} is the
   * same second guard {@link #findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc} documents.
   */
  boolean existsByStatusAndLibraryIdAndOrganizationId(
      JobStatus status, UUID libraryId, UUID organizationId);

  /**
   * Every run for {@code libraryId}, newest first - used only by {@code
   * IndexingJobService#pruneOldRuns}, which must see every row beyond the retained last 10 to
   * delete them. Never used to answer the run-history endpoint - see {@link
   * #findTop10ByLibraryIdAndOrganizationIdOrderByStartedAtDesc} for that: a pre-existing library
   * with hundreds of historical rows would otherwise load every one of them, plus one {@code
   * IndexingRunEventRepository} query per row. Not organization-scoped: {@code pruneOldRuns} is
   * only ever called right after {@code startJob} inserted a row for a {@code libraryId} the caller
   * already resolved and authorized, so every row this returns necessarily shares its organization.
   */
  List<IndexingJob> findByLibraryIdOrderByStartedAtDesc(UUID libraryId);

  /**
   * The last {@value IndexingJobService#MAX_RETAINED_RUNS_PER_LIBRARY} runs for {@code libraryId}
   * within {@code organizationId}, newest first - bounded at the query itself rather than by
   * truncating an unbounded list in Java. {@code organizationId} is the same second guard {@link
   * #findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc} documents.
   */
  List<IndexingJob> findTop10ByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
      UUID libraryId, UUID organizationId);

  /**
   * The last two {@link JobTriggerSource#SCHEDULED} runs for {@code libraryId} within {@code
   * organizationId}, newest first - {@code KnowledgeLibraryService} uses this to compute {@code
   * LibraryResponse.lastScheduledRunsFailed} without loading every run. Two, not one: a single
   * failed scheduled run is not "wiederholtes Scheitern"; it only becomes visible once the schedule
   * has failed twice in a row.
   */
  List<IndexingJob> findTop2ByLibraryIdAndOrganizationIdAndTriggeredByOrderByStartedAtDesc(
      UUID libraryId, UUID organizationId, JobTriggerSource triggeredBy);

  /**
   * Fails every currently {@link JobStatus#RUNNING} row, unconditionally. Called once, right after
   * application startup: a fresh JVM cannot possibly still be running the {@code @Async} task a
   * {@code RUNNING} row refers to - that task lived in the previous process. Leaving such a row
   * {@code RUNNING} forever locks its library out of every future trigger (partial unique index,
   * {@code IndexingJobService#isJobRunning}) with no way to resolve it from the UI - see {@code
   * IndexingJobRecoveryScheduler#recoverOnStartup}.
   *
   * <p>A bulk {@code UPDATE}, not load-then-save: this can affect every library's stuck row in one
   * statement, mirroring {@code DocumentRepository#markFailed}'s reasoning.
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
   * heartbeat is older than {@code cutoff}. Unlike {@link #failAllRunningJobs}, this runs
   * periodically while the application keeps running, not only once at startup - it is the only
   * guard against a run that is orphaned without a restart: a task silently dropped by a full
   * queue, or one that hangs indefinitely.
   *
   * <p>Filters on {@code lastProgressAt}, not {@code startedAt}: a genuinely active run of a large
   * bestand can easily exceed {@code IndexingProperties#staleJobTimeout()} in wall-clock age alone,
   * and {@code updateProgress} touches {@code lastProgressAt} on every file/entry it processes - so
   * this only ever catches a run that has actually stopped making progress.
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
   * Completes {@code id} only if it is still {@link JobStatus#RUNNING}. Without this guard, a job
   * the stale-run sweep or startup recovery already failed - while its executor thread, unaware,
   * kept running - would have that thread's eventual {@code completeJob} silently flip the row back
   * from {@code FAILED} to {@code COMPLETED}. Mirrors {@code DocumentRepository#markIndexed}'s same
   * conditional-update shape and reasoning.
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
   * Fails {@code id} only if it is still {@link JobStatus#RUNNING} - the same reasoning and shape
   * as {@link #completeIfRunning}, for the failure path.
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
