package io.opaa.indexing.trigger;

import io.opaa.common.ConflictException;
import io.opaa.common.DatabaseClock;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.LibraryScheduleCodec;
import io.opaa.instance.ScheduledRunLocks;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers a due connector library's indexing run on the schedule stored on the library itself. A
 * periodic tick, not a per-library registered trigger, so nothing has to be kept in sync when a
 * schedule changes or the application restarts.
 *
 * <p><b>One due time, one run, whichever instance ticks</b> (ADR-0053, Entscheidung 7): the tick
 * books the due time on the library with a conditional {@code UPDATE} ({@link
 * KnowledgeLibraryRepository#bookScheduledRun}) and only the booking call triggers. {@code
 * uk_indexing_jobs_library_running} still keeps two runs of one library from overlapping; the
 * booking keeps a short run from starting again for the same due time.
 *
 * <p>The tick looks back {@link #CATCH_UP} from the database clock for a schedule it booked before,
 * so a tick that comes late - a scheduler thread held up by another run, a minute without any tick
 * - still books the due time it would have missed; a due time older than that is skipped, not
 * caught up. A schedule never booked under its current cron looks back one {@link #TICK_INTERVAL}
 * only, so a schedule just set does not fire for a due time that lay before it was set.
 */
@Component
public class LibraryIndexingScheduler {

  private static final Logger log = LoggerFactory.getLogger(LibraryIndexingScheduler.class);

  static final String SCHEDULE_SKIPPED_MESSAGE =
      "Geplanter Lauf übersprungen: Indizierung läuft bereits";

  /** Matches the {@code @Scheduled(cron = "0 * * * * *")} tick interval below. */
  static final Duration TICK_INTERVAL = Duration.ofMinutes(1);

  /** How far back a tick books a missed due time of a schedule booked before. */
  static final Duration CATCH_UP = Duration.ofMinutes(15);

  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentIndexingService indexingService;
  private final IndexingJobService indexingJobService;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final DatabaseClock databaseClock;
  private final ZoneId zone;

  public LibraryIndexingScheduler(
      KnowledgeLibraryRepository libraryRepository,
      DocumentIndexingService indexingService,
      IndexingJobService indexingJobService,
      IndexingRunEventRepository indexingRunEventRepository,
      DatabaseClock databaseClock,
      Clock clock) {
    this.libraryRepository = libraryRepository;
    this.indexingService = indexingService;
    this.indexingJobService = indexingJobService;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.databaseClock = databaseClock;
    this.zone = clock.getZone();
  }

  /**
   * Runs at the top of every minute, the finest grain any intervalstufe needs; the cron of a
   * library is read in the zone of the {@link Clock}, which every instance shares. The evaluation
   * runs inside the per-library {@code try/catch}, so one undecodable expression cannot abort the
   * tick and leave every other due library untouched. Without an embedding model the tick books
   * nothing: a run could not embed, and the next due time after the model is set runs as usual.
   */
  @Scheduled(cron = "0 * * * * *")
  @SchedulerLock(
      name = "library-indexing-schedule",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void triggerDueLibraries() {
    if (indexingJobService.isEmbeddingModelMissing()) {
      return;
    }
    List<KnowledgeLibrary> scheduled = libraryRepository.findByScheduleEnabledTrue();
    Instant now = databaseClock.now();
    for (KnowledgeLibrary library : scheduled) {
      try {
        Instant due = dueTime(library, now);
        if (due == null
            || libraryRepository.bookScheduledRun(library.getId(), library.getScheduleCron(), due)
                == 0) {
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
  }

  /**
   * The latest fire time of the library's cron in the look-back window up to {@code now}, or {@code
   * null} when there is none or it is already booked.
   */
  private Instant dueTime(KnowledgeLibrary library, Instant now) {
    String cron = library.getScheduleCron();
    if (cron == null) {
      return null;
    }
    Instant booked =
        cron.equals(library.getScheduleBookedCron()) ? library.getScheduleBookedAt() : null;
    Instant windowStart = now.minus(booked == null ? TICK_INTERVAL : CATCH_UP);
    if (booked != null && booked.isAfter(windowStart)) {
      windowStart = booked;
    }
    Instant due = null;
    Instant next = LibraryScheduleCodec.nextRunAt(cron, windowStart, zone);
    while (next != null && !next.isAfter(now)) {
      due = next;
      next = LibraryScheduleCodec.nextRunAt(cron, next, zone);
    }
    return due;
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
      // manual or pushed run could have started between them.
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
