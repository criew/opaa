package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.common.ConflictException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.TaskScheduler;

/**
 * The intake's contract, once for every connector with a push path: one debounced run per library
 * for any number of notifications, the targeted run below the batch bound and the executor's
 * ordinary run above it, a wait - then a drop - while another run is in progress, and a failed job
 * when the executor queue rejects the run.
 */
class SourceEventIntakeTest {

  private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

  private KnowledgeLibraryRepository libraryRepository;
  private IndexingJobService indexingJobService;
  private SourceIndexingExecutor executor;
  private SourceEventTarget target;
  private TaskScheduler scheduler;
  private final List<Runnable> scheduled = new ArrayList<>();
  private SourceEventIntake intake;
  private KnowledgeLibrary library;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    indexingJobService = mock(IndexingJobService.class);
    executor = mock(SourceIndexingExecutor.class);
    target = mock(SourceEventTarget.class);
    when(target.sourceType()).thenReturn(DocumentSourceType.CONFLUENCE);
    when(target.executor()).thenReturn(executor);
    when(target.targetedRunMode()).thenReturn(IndexingRunMode.INCREMENTAL);
    when(executor.defaultRunMode(any())).thenReturn(IndexingRunMode.FULL);
    scheduler = mock(TaskScheduler.class);
    when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
        .thenAnswer(
            inv -> {
              scheduled.add(inv.getArgument(0));
              return null;
            });
    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            "https://wiki.example.org",
            null,
            "token",
            false);
    library.setWebhookSecret("geheim");
    libraryId = UUID.randomUUID();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(indexingJobService.startJob(any(), any(), any(), any()))
        .thenReturn(new IndexingJob(JobStatus.RUNNING));
    intake =
        new SourceEventIntake(
            libraryRepository,
            indexingJobService,
            new SourceEventProperties(Duration.ofSeconds(5), 3, 2),
            scheduler,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void enqueue(String... keys) {
    intake.enqueue(target, libraryId, Set.of(keys), 0);
  }

  @Test
  void collectsNotificationsForTheDebounceAndStartsOneTargetedRun() {
    enqueue("102");
    enqueue("103", "102");
    intake.enqueue(target, libraryId, Set.of("104"), 2);

    ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
    verify(scheduler, times(1)).schedule(any(Runnable.class), at.capture());
    assertThat(at.getValue()).isEqualTo(NOW.plusSeconds(5));
    verifyNoInteractions(indexingJobService);

    scheduled.get(0).run();

    verify(indexingJobService)
        .startJob(
            library.getId(),
            library.getOrganizationId(),
            JobTriggerSource.WEBHOOK,
            IndexingRunMode.INCREMENTAL);
    verify(target).refresh(any(), eq(library), eq(Set.of("102", "103", "104")), eq(2));
    verify(executor, never()).execute(any(), any(), any());
  }

  @Test
  void aBatchBeyondTheBoundRunsTheExecutorsOrdinaryRunInItsDefaultMode() {
    intake.enqueue(target, libraryId, Set.of("1", "2"), 1);
    intake.enqueue(target, libraryId, Set.of("3", "4"), 1);
    scheduled.get(0).run();

    verify(indexingJobService)
        .startJob(any(), any(), eq(JobTriggerSource.WEBHOOK), eq(IndexingRunMode.FULL));
    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.FULL));
    verify(target, never()).refresh(any(), any(), any(), anyInt());

    // the mode is asked of the executor at drain time, from the library's state then
    when(executor.defaultRunMode(library)).thenReturn(IndexingRunMode.INCREMENTAL);
    enqueue("1", "2", "3", "4");
    scheduled.get(1).run();

    verify(indexingJobService)
        .startJob(any(), any(), eq(JobTriggerSource.WEBHOOK), eq(IndexingRunMode.INCREMENTAL));
    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.INCREMENTAL));
  }

  @Test
  void aLibraryOfAnotherSourceTypeIsDroppedAtDrainTime() {
    when(target.sourceType()).thenReturn(DocumentSourceType.S3);
    enqueue("102");

    scheduled.get(0).run();

    verifyNoInteractions(indexingJobService, executor);
    verify(target, never()).refresh(any(), any(), any(), anyInt());
  }

  @Test
  void waitsWhileARunIsInProgressAndDropsTheBatchAfterTheLastDeferral() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    enqueue("102");

    scheduled.get(0).run();
    assertThat(scheduled).as("deferred once").hasSize(2);
    scheduled.get(1).run();
    assertThat(scheduled).as("deferred twice - the configured maximum").hasSize(3);
    scheduled.get(2).run();
    assertThat(scheduled).as("dropped, not rescheduled").hasSize(3);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());

    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(false);
    enqueue("102");
    scheduled.get(3).run();
    verify(target).refresh(any(), eq(library), eq(Set.of("102")), eq(0));
  }

  @Test
  void notificationsArrivingDuringAWaitJoinTheBatchWithoutResettingTheDeferralCount() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    enqueue("102");
    scheduled.get(0).run(); // deferral 1 -> scheduled[1]
    intake.enqueue(target, libraryId, Set.of("103"), 1); // joins the waiting batch
    scheduled.get(1).run(); // deferral 2 (the maximum) -> scheduled[2]
    enqueue("104");
    scheduled.get(2).run(); // dropped

    assertThat(scheduled).as("no fourth schedule: the batch was dropped, not reset").hasSize(3);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
  }

  @Test
  void aBatchThatJoinedDuringADrainKeepsItsKeysAndDroppedCount() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    intake.enqueue(target, libraryId, Set.of("102"), 1);
    Runnable firstDrain = scheduled.get(0);
    // the second notification arrives after the first batch left the queue but before it was
    // deferred: the deferral must fold the older batch into the newer one, losing nothing
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenAnswer(
            inv -> {
              intake.enqueue(target, libraryId, Set.of("103"), 2);
              return true;
            });
    firstDrain.run();

    assertThat(scheduled).as("the joined batch owns the only pending timer").hasSize(2);
    doReturn(false)
        .when(indexingJobService)
        .isJobRunning(library.getId(), library.getOrganizationId());
    scheduled.get(1).run();
    verify(target).refresh(any(), eq(library), eq(Set.of("102", "103")), eq(3));
  }

  @Test
  void anOverflowedBatchStaysOverflowedWhenMerged() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    enqueue("1", "2", "3", "4");
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenAnswer(
            inv -> {
              enqueue("5");
              return true;
            });
    scheduled.get(0).run();

    doReturn(false)
        .when(indexingJobService)
        .isJobRunning(library.getId(), library.getOrganizationId());
    scheduled.get(1).run();
    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.FULL));
    verify(target, never()).refresh(any(), any(), any(), anyInt());
  }

  @Test
  void aConflictAtStartIsTreatedLikeARunInProgress() {
    when(indexingJobService.startJob(any(), any(), any(), any()))
        .thenThrow(new ConflictException("läuft bereits"));
    enqueue("102");

    scheduled.get(0).run();

    assertThat(scheduled).hasSize(2);
    verifyNoInteractions(executor);
    verify(target, never()).refresh(any(), any(), any(), anyInt());
  }

  @Test
  void aFullExecutorQueueFailsTheJobItJustStarted() {
    IndexingJob job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(any(), any(), any(), any())).thenReturn(job);
    doThrow(new TaskRejectedException("voll")).when(target).refresh(any(), any(), any(), anyInt());
    enqueue("102");

    scheduled.get(0).run();

    verify(indexingJobService).failJob(eq(job.getId()), any());
  }

  @Test
  void aLibraryWhoseSecretWasRemovedMeanwhileIsDroppedSilently() {
    enqueue("102");
    library.setWebhookSecret(null);

    scheduled.get(0).run();

    verifyNoInteractions(indexingJobService, executor);
    verify(target, never()).refresh(any(), any(), any(), anyInt());
  }

  @Test
  void aFailedLookupAtDrainTimePutsTheBatchBackAsADeferral() {
    intake.enqueue(target, libraryId, Set.of("102", "103"), 1);
    when(libraryRepository.findById(libraryId))
        .thenThrow(new DataAccessResourceFailureException("Datenbank nicht erreichbar"))
        .thenReturn(Optional.of(library));

    scheduled.get(0).run();

    assertThat(scheduled).as("put back, not lost: one more timer").hasSize(2);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
    scheduled.get(1).run();
    verify(target).refresh(any(), eq(library), eq(Set.of("102", "103")), eq(1));
  }

  @Test
  void aFailedLookupCountsAsADeferralAndEndsAtTheBound() {
    enqueue("102");
    when(libraryRepository.findById(libraryId))
        .thenThrow(new DataAccessResourceFailureException("Datenbank nicht erreichbar"));

    scheduled.get(0).run();
    scheduled.get(1).run();
    scheduled.get(2).run();

    assertThat(scheduled).as("two deferrals, then dropped").hasSize(3);
  }

  @Test
  void aLibraryGoneMeanwhileIsDroppedSilently() {
    enqueue("102");
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.empty());

    scheduled.get(0).run();

    verifyNoInteractions(indexingJobService, executor);
  }
}
