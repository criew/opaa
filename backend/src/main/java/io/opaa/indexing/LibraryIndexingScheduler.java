package io.opaa.indexing;

import io.opaa.common.ConflictException;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers a due connector library's indexing run automatically, on the schedule stored on the
 * library itself. A periodic tick, following {@code io.opaa.audit.AuditRetentionScheduler} - not a
 * per-library dynamically registered trigger: {@code KnowledgeLibraryRepository} already tells this
 * class which libraries are due on every tick, so there is nothing to keep in sync when a schedule
 * changes, a library is deleted, or the application restarts.
 *
 * <p>No leader election, no distributed lock: multiple backend instances ticking the same due
 * library at the same minute would race to insert a {@code RUNNING} row; {@code
 * uk_indexing_jobs_library_running} already makes that race safe - the loser's {@link
 * IndexingJobService#startJob(java.util.UUID, java.util.UUID, JobTriggerSource,
 * io.opaa.api.types.IndexingRunMode)} call fails with the same 409 a second concurrent manual
 * trigger gets: no new run, one {@link IndexingEventCategory#SCHEDULE_SKIPPED} event on the run
 * that is already going. Assumes exactly one backend process overall; see ADR-0021.
 *
 * <p>Never disables a schedule on failure: a run that fails leaves the schedule as-is; it tries
 * again at the next due time. {@code KnowledgeLibraryService#toLibraryResponse} is what makes
 * repeated failure visible in the UI (via {@code lastScheduledRunsFailed}), not this class turning
 * the schedule off.
 *
 * <p>Missed due times are skipped, not caught up: a due time that falls while the application is
 * not running at all is simply never fired - {@link #lastTickAt} resets to {@code null} on every
 * restart, so the very next tick only ever looks one tick-interval into the past (see {@link
 * #determineWindowStart}). Catching up every missed run after a longer outage would risk a burst of
 * simultaneous triggers across every schedule-enabled library at once - an operator who needs the
 * latest content after maintenance still has the manual "Jetzt indizieren" trigger. What this field
 * does guard against is in-process jitter between two consecutive ticks: without it, a fixed
 * one-minute look-back window anchored purely on "now minus 60s" can develop a gap between two
 * ticks that fired more than 60s apart, silently dropping a due time that fell exactly in that gap.
 */
@Component
public class LibraryIndexingScheduler {

  private static final Logger log = LoggerFactory.getLogger(LibraryIndexingScheduler.class);

  static final String SCHEDULE_SKIPPED_MESSAGE =
      "Geplanter Lauf übersprungen: Indizierung läuft bereits";

  /** Matches the {@code @Scheduled(cron = "0 * * * * *")} tick interval below. */
  private static final long TICK_INTERVAL_SECONDS = 60;

  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentIndexingService indexingService;
  private final IndexingJobService indexingJobService;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final Clock clock;

  /**
   * The {@code now} of the previous successful tick, {@code null} before the first tick since
   * application start (see the class Javadoc's "missed due times" paragraph). An {@link
   * AtomicReference}, not a plain field, purely for its safe-publication guarantee across the
   * scheduler thread - {@code @Scheduled} methods on the default single-threaded {@code
   * TaskScheduler} never actually run concurrently with each other, so no compare-and-set semantics
   * are needed, only visibility.
   */
  private final AtomicReference<Instant> lastTickAt = new AtomicReference<>();

  public LibraryIndexingScheduler(
      KnowledgeLibraryRepository libraryRepository,
      DocumentIndexingService indexingService,
      IndexingJobService indexingJobService,
      IndexingRunEventRepository indexingRunEventRepository,
      Clock clock) {
    this.libraryRepository = libraryRepository;
    this.indexingService = indexingService;
    this.indexingJobService = indexingJobService;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.clock = clock;
  }

  /**
   * Runs at the top of every minute (the finest grain any of the four intervalstufen needs), server
   * local time ({@link Clock#getZone()} of the injected {@link Clock}, see {@code
   * io.opaa.indexing.IndexingConfiguration#schedulingClock}). A library is due when its stored cron
   * expression has a fire time in the window between the previous tick and now - see {@link
   * #determineWindowStart} and {@link #isDueNow}.
   *
   * <p>{@link #isDueNow} - and therefore the cron parse it performs - runs inside this loop's own
   * per-library {@code try/catch}, not before it: one library with an undecodable stored cron
   * expression must not abort the whole tick and leave every other due library untouched.
   */
  @Scheduled(cron = "0 * * * * *")
  public void triggerDueLibraries() {
    List<KnowledgeLibrary> scheduled = libraryRepository.findByScheduleEnabledTrue();
    Instant now = clock.instant();
    Instant windowStart = determineWindowStart(now);
    for (KnowledgeLibrary library : scheduled) {
      try {
        if (!isDueNow(library.getScheduleCron(), windowStart, now)) {
          continue;
        }
        triggerOrRecordSkip(library);
      } catch (Exception e) {
        log.error(
            "Scheduled tick could not evaluate library {} - skipping it for this tick",
            library.getId(),
            e);
      }
    }
    lastTickAt.set(now);
  }

  /**
   * The start of the window {@link #isDueNow} checks a schedule against - the previous tick's
   * {@code now} when known, so consecutive windows are contiguous with no gap regardless of how
   * late a tick actually fired (see the class Javadoc). Falls back to exactly one {@link
   * #TICK_INTERVAL_SECONDS} before {@code now} before the first tick (or after a restart, since
   * {@link #lastTickAt} does not survive one) - deliberately not further back, see the class
   * Javadoc's "missed due times are skipped, not caught up" paragraph.
   */
  private Instant determineWindowStart(Instant now) {
    Instant previous = lastTickAt.get();
    return previous != null ? previous : now.minusSeconds(TICK_INTERVAL_SECONDS);
  }

  private boolean isDueNow(String cron, Instant windowStart, Instant now) {
    if (cron == null) {
      return false;
    }
    Instant next = LibraryScheduleCodec.nextRunAt(cron, windowStart, clock.getZone());
    return next != null && !next.isAfter(now);
  }

  private void triggerOrRecordSkip(KnowledgeLibrary library) {
    if (indexingJobService.isJobRunning(library.getId(), library.getOrganizationId())) {
      recordSkipEvent(library);
      return;
    }
    try {
      indexingService.triggerScheduledIndexing(library);
    } catch (ConflictException e) {
      // TOCTOU: the pre-check above and startJob's own insert are two separate statements - a
      // run could have started between them (another backend instance racing the same tick).
      recordSkipEvent(library);
    } catch (Exception e) {
      log.error("Scheduled indexing trigger for library {} failed", library.getId(), e);
    }
  }

  /**
   * Records {@link IndexingEventCategory#SCHEDULE_SKIPPED} against the library's currently RUNNING
   * job, not a new run of its own - a skip is not a run, so it gets no {@link IndexingJob} row. A
   * no-op if no RUNNING job is found any more by the time this executes.
   */
  private void recordSkipEvent(KnowledgeLibrary library) {
    Optional<IndexingJob> runningJob =
        indexingJobService
            .getLatestJob(library.getId(), library.getOrganizationId())
            .filter(job -> job.getStatus() == JobStatus.RUNNING);
    runningJob.ifPresentOrElse(
        job ->
            indexingRunEventRepository.save(
                new IndexingRunEvent(
                    job.getId(),
                    IndexingEventCategory.SCHEDULE_SKIPPED,
                    SCHEDULE_SKIPPED_MESSAGE,
                    null)),
        () ->
            log.debug(
                "Scheduled tick for library {} found no RUNNING job to record the skip against"
                    + " any more",
                library.getId()));
  }
}
