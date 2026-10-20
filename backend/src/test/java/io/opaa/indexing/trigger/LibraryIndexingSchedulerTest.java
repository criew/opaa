package io.opaa.indexing.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ScheduleFrequency;
import io.opaa.common.ConflictException;
import io.opaa.common.DatabaseClock;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.LibraryScheduleCodec;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.SourceTypes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link LibraryIndexingScheduler}'s own responsibility is narrow - decide which libraries are due
 * on a tick, and either trigger a run or record a skip event. Everything downstream (which executor
 * runs, how a run's own counters change) belongs to {@link DocumentIndexingService} and is
 * exercised by that class's own tests. Uses a fixed {@link Clock} throughout (per AGENTS.md: unit
 * tests for a scheduler's core use a stelled clock, never real waiting).
 */
class LibraryIndexingSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");

  private KnowledgeLibraryRepository libraryRepository;
  private DocumentIndexingService indexingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository indexingRunEventRepository;
  private LibraryIndexingScheduler scheduler;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    indexingService = mock(DocumentIndexingService.class);
    indexingJobService = mock(IndexingJobService.class);
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    when(libraryRepository.bookScheduledRun(any(), any(), any())).thenReturn(1);
    scheduler = schedulerAt(() -> NOW);
  }

  private LibraryIndexingScheduler schedulerAt(DatabaseClock databaseClock) {
    return new LibraryIndexingScheduler(
        libraryRepository,
        indexingService,
        indexingJobService,
        indexingRunEventRepository,
        databaseClock,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** The library as a tick of some instance left it after booking {@code bookedAt}. */
  private static void booked(KnowledgeLibrary library, Instant bookedAt) {
    ReflectionTestUtils.setField(library, "scheduleBookedAt", bookedAt);
    ReflectionTestUtils.setField(library, "scheduleBookedCron", library.getScheduleCron());
  }

  private KnowledgeLibrary dueLibrary() {
    // 3:00 UTC matches the DAILY 03:00 cron this test configures below - a tick landing exactly on
    // NOW must find it due.
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Rechtsquellen",
            null,
            UUID.randomUUID(),
            SourceTypes.HTTP_DIRECTORY,
            null,
            "https://example.org/docs/",
            null);
    library.updateSchedule(true, LibraryScheduleCodec.toCron(ScheduleFrequency.DAILY, 3, 0, null));
    return library;
  }

  @Test
  void triggersADueLibraryThatIsNotAlreadyRunning() {
    KnowledgeLibrary library = dueLibrary();
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(false);

    scheduler.triggerDueLibraries();

    verify(indexingService).triggerScheduledIndexing(library);
    verify(indexingRunEventRepository, never()).save(any());
  }

  @Test
  void skipsALibraryThatIsAlreadyRunningAndRecordsAnEventOnTheRunningJob() {
    KnowledgeLibrary library = dueLibrary();
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    IndexingJob runningJob = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.getLatestJob(library.getId(), library.getOrganizationId()))
        .thenReturn(Optional.of(runningJob));

    scheduler.triggerDueLibraries();

    verify(indexingService, never()).triggerScheduledIndexing(any());
    ArgumentCaptor<IndexingRunEvent> eventCaptor = ArgumentCaptor.forClass(IndexingRunEvent.class);
    verify(indexingRunEventRepository).save(eventCaptor.capture());
    IndexingRunEvent event = eventCaptor.getValue();
    assertThat(event.getJobId()).isEqualTo(runningJob.getId());
    assertThat(event.getCategory()).isEqualTo(IndexingEventCategory.SCHEDULE_SKIPPED);
    assertThat(event.getMessage()).isEqualTo(LibraryIndexingScheduler.SCHEDULE_SKIPPED_MESSAGE);
  }

  @Test
  void recordsTheSkipEventEvenWhenTheStartJobRaceLosesAfterThePreCheckPassed() {
    KnowledgeLibrary library = dueLibrary();
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(false);
    when(indexingService.triggerScheduledIndexing(library))
        .thenThrow(
            new ConflictException("Für diese Bibliothek läuft bereits ein Indizierungslauf"));
    IndexingJob runningJob = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.getLatestJob(library.getId(), library.getOrganizationId()))
        .thenReturn(Optional.of(runningJob));

    scheduler.triggerDueLibraries();

    ArgumentCaptor<IndexingRunEvent> eventCaptor = ArgumentCaptor.forClass(IndexingRunEvent.class);
    verify(indexingRunEventRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getJobId()).isEqualTo(runningJob.getId());
    assertThat(eventCaptor.getValue().getCategory())
        .isEqualTo(IndexingEventCategory.SCHEDULE_SKIPPED);
  }

  @Test
  void doesNotTriggerALibraryThatIsNotDueYet() {
    KnowledgeLibrary library = dueLibrary();
    // The stored schedule fires at 03:00; a tick at 02:59 must not treat it as due.
    scheduler = schedulerAt(() -> NOW.minusSeconds(60));
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));

    scheduler.triggerDueLibraries();

    verify(indexingService, never()).triggerScheduledIndexing(any());
    verify(indexingJobService, never()).isJobRunning(any(), any());
  }

  // An undecodable stored cron expression must not abort the whole
  // tick - every other due library is still evaluated and triggered.
  @Test
  void aLibraryWithAnUndecodableCronDoesNotAbortTheWholeTick() {
    KnowledgeLibrary defective = dueLibrary();
    defective.updateSchedule(true, "not a cron expression at all");
    KnowledgeLibrary healthy = dueLibrary();
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(defective, healthy));
    when(indexingJobService.isJobRunning(healthy.getId(), healthy.getOrganizationId()))
        .thenReturn(false);

    scheduler.triggerDueLibraries();

    verify(indexingService).triggerScheduledIndexing(healthy);
    verify(indexingService, never()).triggerScheduledIndexing(defective);
  }

  // A schedule never booked under its current cron - just set or just changed - looks back one tick
  // interval only, so it does not fire for a due time that lay before it was set.
  @Test
  void aScheduleNeverBookedDoesNotCatchUpADueTimeFromBeforeTheLastTickInterval() {
    KnowledgeLibrary library = dueLibrary();
    // Due at 03:00; the tick at 03:05 is five minutes after it, outside the one tick interval.
    scheduler = schedulerAt(() -> NOW.plusSeconds(5 * 60));
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));

    scheduler.triggerDueLibraries();

    verify(indexingService, never()).triggerScheduledIndexing(any());
  }

  // A tick that comes late - the scheduler thread held up by another run, or a minute in which no
  // instance got to tick - still books the due time of a schedule that was booked before: the
  // look-back reaches CATCH_UP behind the database clock, not just one tick interval.
  @Test
  void aLateTickStillBooksTheMissedDueTimeOfAScheduleBookedBefore() {
    KnowledgeLibrary library = dueLibrary();
    booked(library, NOW.minus(Duration.ofDays(1)));
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(false);
    scheduler = schedulerAt(() -> NOW.plusSeconds(5 * 60));

    scheduler.triggerDueLibraries();

    verify(libraryRepository).bookScheduledRun(library.getId(), library.getScheduleCron(), NOW);
    verify(indexingService, times(1)).triggerScheduledIndexing(library);
  }

  // A late tick never reaches further back than CATCH_UP: a due time missed for longer is skipped.
  @Test
  void aTickLaterThanTheCatchUpDoesNotBookTheMissedDueTime() {
    KnowledgeLibrary library = dueLibrary();
    booked(library, NOW.minus(Duration.ofDays(1)));
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    scheduler = schedulerAt(() -> NOW.plus(LibraryIndexingScheduler.CATCH_UP).plusSeconds(1));

    scheduler.triggerDueLibraries();

    verify(libraryRepository, never()).bookScheduledRun(any(), any(), any());
    verify(indexingService, never()).triggerScheduledIndexing(any());
  }

  // A due time another instance already booked is not triggered again: the booking, not the tick,
  // decides, so a short run starts once per due time however many instances tick.
  @Test
  void aDueTimeAnotherInstanceAlreadyBookedIsNotTriggeredAgain() {
    KnowledgeLibrary library = dueLibrary();
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    when(libraryRepository.bookScheduledRun(library.getId(), library.getScheduleCron(), NOW))
        .thenReturn(0);

    scheduler.triggerDueLibraries();

    verify(indexingService, never()).triggerScheduledIndexing(any());
    verify(indexingJobService, never()).isJobRunning(any(), any());
    verify(indexingRunEventRepository, never()).save(any());
  }

  // The window starts after the last booking, so a booked due time is not even offered again.
  @Test
  void aDueTimeThisLibraryAlreadyBookedIsNotOfferedAgain() {
    KnowledgeLibrary library = dueLibrary();
    booked(library, NOW);
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    scheduler = schedulerAt(() -> NOW.plusSeconds(30));

    scheduler.triggerDueLibraries();

    verify(libraryRepository, never()).bookScheduledRun(any(), any(), any());
    verify(indexingService, never()).triggerScheduledIndexing(any());
  }
}
