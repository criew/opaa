package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.ScheduleFrequency;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * #485: {@link LibraryIndexingScheduler}'s own responsibility is narrow - decide which libraries
 * are due on a tick, and either trigger a run or record a skip event. Everything downstream (which
 * executor runs, how a run's own counters change) belongs to {@link DocumentIndexingService} and is
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
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    scheduler =
        new LibraryIndexingScheduler(
            libraryRepository,
            indexingService,
            indexingJobService,
            indexingRunEventRepository,
            clock);
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
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            "https://example.org/docs/",
            null,
            null,
            false);
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
            new ResponseStatusException(
                HttpStatus.CONFLICT, "Für diese Bibliothek läuft bereits ein Indizierungslauf"));
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
    Clock oneMinuteEarly = Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC);
    scheduler =
        new LibraryIndexingScheduler(
            libraryRepository,
            indexingService,
            indexingJobService,
            indexingRunEventRepository,
            oneMinuteEarly);
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));

    scheduler.triggerDueLibraries();

    verify(indexingService, never()).triggerScheduledIndexing(any());
    verify(indexingJobService, never()).isJobRunning(any(), any());
  }

  // PR #705 review, blocker 3: an undecodable stored cron expression must not abort the whole
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

  // PR #705 review, item 4: verpasste Termine werden ausgelassen, nicht nachgeholt - a fresh
  // scheduler (simulating a restart) only ever looks one tick-interval into the past on its first
  // tick, never further back, however long the library's due time actually lies in the past.
  @Test
  void aFreshTickAfterARestartDoesNotCatchUpADueTimeFromLongBeforeItStarted() {
    KnowledgeLibrary library = dueLibrary();
    // Due at 03:00, but this fresh scheduler's very first tick only starts at 03:05 - five
    // minutes after the due time, well outside the single tick-interval look-back a first tick
    // gets.
    Clock fiveMinutesLate = Clock.fixed(NOW.plusSeconds(5 * 60), ZoneOffset.UTC);
    scheduler =
        new LibraryIndexingScheduler(
            libraryRepository,
            indexingService,
            indexingJobService,
            indexingRunEventRepository,
            fiveMinutesLate);
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));

    scheduler.triggerDueLibraries();

    verify(indexingService, never()).triggerScheduledIndexing(any());
  }

  // PR #705 review, item 4: a fixed "now minus 60s" window can develop a gap between two ticks
  // that fired more than 60 seconds apart (a slow previous tick, scheduler thread contention) -
  // tracking the previous tick's own `now` as the next window's start closes that gap instead.
  // Here the due time (NOW, 03:00:00) lies 90s before the first tick and 65s before the second -
  // a plain "now minus 60s" window on the second tick alone would start at 03:00:05, strictly
  // after the due time, and miss it; continuing from the first tick's own `now` (02:58:30)
  // still covers it.
  @Test
  void aDelayedSecondTickStillCatchesADueTimeThatANaiveFixedWindowWouldHaveMissed() {
    KnowledgeLibrary library = dueLibrary();
    when(libraryRepository.findByScheduleEnabledTrue()).thenReturn(List.of(library));
    AtomicReference<Instant> now = new AtomicReference<>(NOW.minusSeconds(90));
    scheduler =
        new LibraryIndexingScheduler(
            libraryRepository,
            indexingService,
            indexingJobService,
            indexingRunEventRepository,
            new MutableClock(now, ZoneOffset.UTC));
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(false);

    // First tick, 90s before the due time - establishes lastTickAt, nothing due yet.
    scheduler.triggerDueLibraries();
    verify(indexingService, never()).triggerScheduledIndexing(any());

    // Second tick, 105s later (45s of jitter beyond the nominal 60s interval) - now 65s past the
    // due time. A fresh "now minus 60s" window would start after the due time and miss it; the
    // window continuing from the first tick's own `now` still reaches back far enough.
    now.set(NOW.plusSeconds(65));
    scheduler.triggerDueLibraries();

    verify(indexingService, times(1)).triggerScheduledIndexing(library);
  }

  /**
   * A {@link Clock} whose {@link #instant()} reflects a caller-mutated reference, for tests that
   * need to advance time between two calls to the scheduler without waiting in real time.
   */
  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    MutableClock(AtomicReference<Instant> now, ZoneId zone) {
      this.now = now;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
