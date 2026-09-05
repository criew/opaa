package io.opaa.indexing;

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

public interface IndexingJobRepository extends JpaRepository<IndexingJob, UUID> {

  /**
   * The most recent run for {@code libraryId} within {@code organizationId}, or empty if none
   * matches (concurrency and status are per library, not global). {@code organizationId} is a
   * second, independent guard: the composite foreign key already forbids a library from another
   * organization, but this does not rely on whatever authorized {@code libraryId} in the first
   * place.
   */
  Optional<IndexingJob> findTopByLibraryIdAndOrganizationIdOrderByStartedAtDesc(
      UUID libraryId, UUID organizationId);

  /**
   * The most recent run for {@code libraryId} that assessed its source listing - {@code
   * listing_complete IS NOT NULL}, written only by a successful, non-truncated full sync.
   * Deliberately not the most recent run overall: an incremental or webhook run in between never
   * assesses the listing, and the warning at the library must survive it.
   */
  Optional<IndexingJob>
      findTopByLibraryIdAndOrganizationIdAndListingCompleteIsNotNullOrderByStartedAtDesc(
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
   * Every run for {@code libraryId}, newest first - only for {@code
   * IndexingJobService#pruneOldRuns}, which must see every row beyond the retained last 10. The
   * run-history endpoint uses {@link #findTop10ByLibraryIdAndOrganizationIdOrderByStartedAtDesc}
   * instead. Not organization-scoped, because {@code pruneOldRuns} runs right after {@code
   * startJob} on an already authorized {@code libraryId}.
   */
  List<IndexingJob> findByLibraryIdOrderByStartedAtDesc(UUID libraryId);

  /**
   * Latest successful completion per library in one grouped query - backs the library overview's
   * "Stand" column the same way {@code DocumentRepository#countByLibraryIdIn} backs its
   * documentCount: one query for the whole page, never one per row. Only {@link
   * JobStatus#COMPLETED} rows count; libraries without any completed run simply have no row here
   * and stay {@code null} on the response.
   */
  @Query(
      "select j.libraryId as libraryId, max(j.completedAt) as lastCompletedAt from IndexingJob j"
          + " where j.libraryId in :libraryIds and j.status = io.opaa.indexing.JobStatus.COMPLETED"
          + " group by j.libraryId")
  List<LibraryLastCompleted> findLastCompletedByLibraryIdIn(
      @Param("libraryIds") Collection<UUID> libraryIds);

  interface LibraryLastCompleted {
    UUID getLibraryId();

    Instant getLastCompletedAt();
  }

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
   * Fails every currently {@link JobStatus#RUNNING} row, unconditionally, once right after startup:
   * a fresh JVM cannot still be running the {@code @Async} task such a row refers to, and leaving
   * it {@code RUNNING} would lock its library out of every future trigger with no way to resolve it
   * from the UI. A bulk {@code UPDATE}, so one statement covers every library's stuck row.
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
   * heartbeat is older than {@code cutoff} - the only guard against a run orphaned without a
   * restart. Filters on {@code lastProgressAt}, not {@code startedAt}, since a genuinely active run
   * can exceed {@code staleJobTimeout} in wall-clock age alone.
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
   * Completes {@code id} only if it is still {@link JobStatus#RUNNING}. Without the guard, a job
   * the stale-run sweep or startup recovery already failed would be flipped back from {@code
   * FAILED} to {@code COMPLETED} by its own, unaware executor thread.
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
