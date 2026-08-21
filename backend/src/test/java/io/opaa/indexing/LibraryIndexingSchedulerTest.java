package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.ScheduleFrequency;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
}
