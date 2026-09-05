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
 * Triggers a due connector library's indexing run on the schedule stored on the library itself. A
 * periodic tick, not a per-library registered trigger, so nothing has to be kept in sync when a
 * schedule changes or the application restarts. No leader election: {@code
 * uk_indexing_jobs_library_running} already makes a concurrent trigger safe, and ADR-0021 assumes
 * one backend process.
 *
 * <p>A failed run never disables its schedule, and a missed due time is skipped rather than caught
 * up. {@link #lastTickAt} closes the in-process gap a window anchored purely on "now minus 60s"
 * would leave between two ticks more than 60s apart.
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
   * application start. An {@link AtomicReference} purely for safe publication across the scheduler
   * thread - {@code @Scheduled} methods on the default single-threaded {@code TaskScheduler} never
   * run concurrently, so only visibility is needed, not compare-and-set.
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
   * Runs at the top of every minute, the finest grain any intervalstufe needs, in the injected
   * {@link Clock}'s zone. A library is due when its stored cron expression has a fire time between
   * the previous tick and now (see {@link #determineWindowStart} and {@link #isDueNow}). The cron
   * parse runs inside the per-library {@code try/catch}, so one undecodable expression cannot abort
   * the tick and leave every other due library untouched.
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
   * The start of the window {@link #isDueNow} checks a schedule against: the previous tick's {@code
   * now} when known, so consecutive windows are contiguous however late a tick fired. Falls back to
   * exactly one {@link #TICK_INTERVAL_SECONDS} before {@code now} on the first tick after a start -
   * deliberately not further back, since missed due times are skipped, not caught up.
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
