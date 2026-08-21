package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Triggers a due connector library's indexing run automatically, on the schedule stored on the
 * library itself (#485). A periodic tick, following {@code io.opaa.audit.AuditRetentionScheduler}
 * ("Vorbild") - not a per-library dynamically registered trigger: {@code
 * KnowledgeLibraryRepository} already tells this class which libraries are due on every tick, so
 * there is nothing to keep in sync when a schedule changes, a library is deleted, or the
 * application restarts.
 *
 * <p><b>No leader election, no distributed lock (#485, Zuschnitt 21.08.2026).</b> Multiple backend
 * instances ticking the same due library at the same minute would race to insert a {@code RUNNING}
 * row; {@code uk_indexing_jobs_library_running} (migration 028) already makes that race safe - the
 * loser's {@link IndexingJobService#startJob(java.util.UUID, java.util.UUID, JobTriggerSource)}
 * call fails with the same 409 a second concurrent manual trigger gets, which this class treats
 * identically to its own pre-check finding a run already in progress: no new run, one {@link
 * IndexingEventCategory#SCHEDULE_SKIPPED} event on the run that is already going. Multi-instance
 * scheduling gets a real leader/lock mechanism only once such a deployment actually exists.
 *
 * <p><b>Never disables a schedule on failure (#485, Zuschnitt 21.08.2026).</b> A run that fails
 * leaves the schedule as-is; it tries again at the next due time. {@code
 * KnowledgeLibraryService#toLibraryResponse} is what makes repeated failure visible in the UI (via
 * {@code lastScheduledRunsFailed}), not this class turning the schedule off - stille Ausfälle
 * vermeiden, not silent retries either.
 */
@Component
public class LibraryIndexingScheduler {

  private static final Logger log = LoggerFactory.getLogger(LibraryIndexingScheduler.class);

  static final String SCHEDULE_SKIPPED_MESSAGE =
      "Geplanter Lauf übersprungen: Indizierung läuft bereits";

  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentIndexingService indexingService;
  private final IndexingJobService indexingJobService;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final Clock clock;

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
   * Runs at the top of every minute (#485 - the finest grain any of the four intervalstufen needs),
   * server local time ({@link Clock#getZone()} of the injected {@link Clock}, see {@code
   * io.opaa.indexing.IndexingConfiguration#schedulingClock} - the same "server time, no separate
   * timezone configuration yet" choice {@code AuditRetentionScheduler} already made implicitly for
   * its own {@code @Scheduled(cron = ...)}). A library is due when its stored cron expression has a
   * fire time in the one-minute window ending now - see {@link #isDueNow}.
   */
  @Scheduled(cron = "0 * * * * *")
  public void triggerDueLibraries() {
    List<KnowledgeLibrary> scheduled = libraryRepository.findByScheduleEnabledTrue();
    Instant now = clock.instant();
    for (KnowledgeLibrary library : scheduled) {
      if (!isDueNow(library.getScheduleCron(), now)) {
        continue;
      }
      triggerOrRecordSkip(library);
    }
  }

  private boolean isDueNow(String cron, Instant now) {
    if (cron == null) {
      return false;
    }
    Instant windowStart = now.minusSeconds(60);
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
    } catch (ResponseStatusException e) {
      if (e.getStatusCode() == HttpStatus.CONFLICT) {
        // #500 review, finding 3 style TOCTOU: the pre-check above and startJob's own insert are
        // two separate statements - a run could have started between them (this instance's own
        // recovery/other trigger path, or another backend instance racing the same tick).
        recordSkipEvent(library);
      } else {
        log.error("Scheduled indexing trigger for library {} failed", library.getId(), e);
      }
    } catch (Exception e) {
      log.error("Scheduled indexing trigger for library {} failed", library.getId(), e);
    }
  }

  /**
   * Records {@link IndexingEventCategory#SCHEDULE_SKIPPED} against the library's currently RUNNING
   * job, not a new run of its own (#485, Zuschnitt: "als Ereignis im Laufprotokoll (#604)
   * festgehalten") - a skip is not a run, so it gets no {@link IndexingJob} row. A no-op if no
   * RUNNING job is found any more by the time this executes (the race already resolved itself
   * between the check and here) - nothing meaningful left to attach the event to.
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
