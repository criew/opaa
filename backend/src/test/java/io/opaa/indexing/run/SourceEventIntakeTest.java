package io.opaa.indexing.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ConflictException;
import io.opaa.indexing.credentials.LibrarySourceConnectionResolver;
import io.opaa.indexing.credentials.SourceConnectionResolver;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.JobTriggerSource;
import io.opaa.indexing.spi.SourceIndexingExecutor;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The intake's contract, once for every connector with a push path: one debounced run per library
 * for any number of notifications, the targeted run below the batch bound and the executor's
 * ordinary run above it, a wait - then a drop - while another run is in progress, and a failed job
 * when the executor queue rejects the run. The batches lie in the real table (ADR-0053,
 * Entscheidung 10); further "instances" are intakes built here against the same database.
 *
 * <p>The debounce is an hour, so nothing falls due by itself; {@link #elapse} makes the library's
 * open batch due, standing in for the debounce elapsing. The source type is one no connector of the
 * context registers, so the context's own intake never drains these batches.
 */
@OpaaIntegrationTest
class SourceEventIntakeTest {

  private static final SourceType PUSHED = SourceType.of("PUSH_TEST");
  private static final SourceType OTHER = SourceType.of("PUSH_TEST_OTHER");
  private static final int MAX_KEYS = 3;
  private static final int MAX_DEFERRALS = 2;

  @Autowired private SourceEventBatches batches;
  @Autowired private JdbcTemplate jdbc;

  private KnowledgeLibraryRepository libraryRepository;
  private IndexingJobService indexingJobService;
  private SourceIndexingExecutor executor;
  private SourceEventTarget target;
  private SourceEventIntake intake;
  private KnowledgeLibrary library;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    indexingJobService = mock(IndexingJobService.class);
    executor = mock(SourceIndexingExecutor.class);
    target = mock(SourceEventTarget.class);
    when(target.sourceType()).thenReturn(PUSHED);
    when(target.executor()).thenReturn(executor);
    when(target.targetedRunMode()).thenReturn(IndexingRunMode.INCREMENTAL);
    when(executor.defaultRunMode(any(), any())).thenReturn(IndexingRunMode.FULL);
    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki",
            null,
            UUID.randomUUID(),
            PUSHED,
            null,
            "https://wiki.example.org",
            "token");
    library.setWebhookSecret("geheim");
    libraryId = UUID.randomUUID();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(indexingJobService.startJob(any(), any(), any(), any()))
        .thenAnswer(inv -> new IndexingJob(JobStatus.RUNNING));
    intake = instance(new LibrarySourceConnectionResolver(), SourceEventIntake.LEASE);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM source_event_batches WHERE library_id = ?", libraryId);
  }

  private SourceEventIntake instance(SourceConnectionResolver resolver, Duration lease) {
    SourceEventIntake instance =
        new SourceEventIntake(
            libraryRepository,
            indexingJobService,
            new SourceEventProperties(Duration.ofHours(1), MAX_KEYS, MAX_DEFERRALS),
            batches,
            resolver,
            new DefaultRunModes(
                resolver, mock(SourceSyncStateRepository.class), SyncStateBasis.WHOLE_SETTINGS),
            lease);
    instance.register(target);
    return instance;
  }

  private void enqueue(String... keys) {
    intake.enqueue(target, libraryId, Set.of(keys), 0);
  }

  /** The debounce of the library's open batch has elapsed. */
  private void elapse() {
    jdbc.update(
        "UPDATE source_event_batches SET due_at = now() - interval '1 second'"
            + " WHERE library_id = ? AND claimed_until IS NULL",
        libraryId);
  }

  private List<Integer> deferralsOfTheOpenBatch() {
    return jdbc.queryForList(
        "SELECT deferrals FROM source_event_batches WHERE library_id = ?"
            + " AND claimed_until IS NULL",
        Integer.class,
        libraryId);
  }

  private int batchRows() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM source_event_batches WHERE library_id = ?",
            Integer.class,
            libraryId);
    return count == null ? 0 : count;
  }

  @Test
  void collectsNotificationsForTheDebounceAndStartsOneTargetedRun() {
    enqueue("102");
    enqueue("103", "102");
    intake.enqueue(target, libraryId, Set.of("104"), 2);

    intake.drainDue();
    verifyNoInteractions(indexingJobService);
    assertThat(batchRows()).as("one batch for the library, still in its debounce").isEqualTo(1);

    elapse();
    intake.drainDue();

    verify(indexingJobService)
        .startJob(
            library.getId(),
            library.getOrganizationId(),
            JobTriggerSource.WEBHOOK,
            IndexingRunMode.INCREMENTAL);
    verify(target).refresh(any(), eq(library), eq(Set.of("102", "103", "104")), eq(2));
    verify(executor, never()).execute(any(), any(), any());
    assertThat(batchRows()).isZero();
  }

  @Test
  void aBatchBeyondTheBoundRunsTheExecutorsOrdinaryRunInItsDefaultMode() {
    intake.enqueue(target, libraryId, Set.of("1", "2"), 1);
    intake.enqueue(target, libraryId, Set.of("3", "4"), 1);
    elapse();
    intake.drainDue();

    verify(indexingJobService)
        .startJob(any(), any(), eq(JobTriggerSource.WEBHOOK), eq(IndexingRunMode.FULL));
    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.FULL));
    verify(target, never()).refresh(any(), any(), any(), anyInt());

    // the mode is asked of the executor at drain time, from the library's state then
    when(executor.defaultRunMode(eq(library), any())).thenReturn(IndexingRunMode.INCREMENTAL);
    enqueue("1", "2", "3", "4");
    elapse();
    intake.drainDue();

    verify(indexingJobService)
        .startJob(any(), any(), eq(JobTriggerSource.WEBHOOK), eq(IndexingRunMode.INCREMENTAL));
    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.INCREMENTAL));
  }

  /** A locked source takes the event and drops it - no run, no failed run per event. */
  @Test
  void aBatchForALockedLibraryIsDroppedWithoutARun() {
    SourceConnectionResolver locked =
        new LibrarySourceConnectionResolver() {
          @Override
          public boolean isLocked(KnowledgeLibrary candidate) {
            return true;
          }
        };
    intake = instance(locked, SourceEventIntake.LEASE);
    enqueue("102");
    elapse();

    intake.drainDue();

    verifyNoInteractions(indexingJobService, executor);
    verify(target, never()).refresh(any(), any(), any(), anyInt());
    assertThat(batchRows()).isZero();
  }

  @Test
  void aLibraryOfAnotherSourceTypeIsDroppedAtDrainTime() {
    when(target.sourceType()).thenReturn(OTHER);
    intake.register(target);
    enqueue("102");
    elapse();

    intake.drainDue();

    verifyNoInteractions(indexingJobService, executor);
    verify(target, never()).refresh(any(), any(), any(), anyInt());
    assertThat(batchRows()).isZero();
  }

  @Test
  void waitsWhileARunIsInProgressAndDropsTheBatchAfterTheLastDeferral() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    enqueue("102");

    elapse();
    intake.drainDue();
    assertThat(deferralsOfTheOpenBatch()).as("deferred once").containsExactly(1);
    elapse();
    intake.drainDue();
    assertThat(deferralsOfTheOpenBatch())
        .as("deferred twice - the configured maximum")
        .containsExactly(2);
    elapse();
    intake.drainDue();
    assertThat(batchRows()).as("dropped, not put back").isZero();
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());

    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(false);
    enqueue("102");
    elapse();
    intake.drainDue();
    verify(target).refresh(any(), eq(library), eq(Set.of("102")), eq(0));
  }

  @Test
  void notificationsArrivingDuringAWaitJoinTheBatchWithoutResettingTheDeferralCount() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    enqueue("102");
    elapse();
    intake.drainDue(); // deferral 1
    intake.enqueue(target, libraryId, Set.of("103"), 1); // joins the waiting batch
    elapse();
    intake.drainDue(); // deferral 2 (the maximum)
    enqueue("104");
    elapse();
    intake.drainDue(); // dropped

    assertThat(batchRows()).as("the batch was dropped, not reset").isZero();
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
  }

  @Test
  void aBatchThatJoinedDuringADrainKeepsItsKeysAndDroppedCount() {
    intake.enqueue(target, libraryId, Set.of("102"), 1);
    elapse();
    // the second notification arrives after the first batch was claimed but before it was
    // deferred: the deferral folds the older batch into the newer one, losing nothing
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenAnswer(
            inv -> {
              intake.enqueue(target, libraryId, Set.of("103"), 2);
              return true;
            });
    intake.drainDue();

    assertThat(batchRows()).as("the joined batch is the only one left").isEqualTo(1);
    assertThat(deferralsOfTheOpenBatch()).containsExactly(1);
    doReturn(false)
        .when(indexingJobService)
        .isJobRunning(library.getId(), library.getOrganizationId());
    elapse();
    intake.drainDue();
    verify(target).refresh(any(), eq(library), eq(Set.of("102", "103")), eq(3));
  }

  @Test
  void anOverflowedBatchStaysOverflowedWhenMerged() {
    enqueue("1", "2", "3", "4");
    elapse();
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenAnswer(
            inv -> {
              enqueue("5");
              return true;
            });
    intake.drainDue();

    doReturn(false)
        .when(indexingJobService)
        .isJobRunning(library.getId(), library.getOrganizationId());
    elapse();
    intake.drainDue();
    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.FULL));
    verify(target, never()).refresh(any(), any(), any(), anyInt());
  }

  @Test
  void aConflictAtStartIsTreatedLikeARunInProgress() {
    when(indexingJobService.startJob(any(), any(), any(), any()))
        .thenThrow(new ConflictException("läuft bereits"));
    enqueue("102");
    elapse();

    intake.drainDue();

    assertThat(deferralsOfTheOpenBatch()).containsExactly(1);
    verifyNoInteractions(executor);
    verify(target, never()).refresh(any(), any(), any(), anyInt());
  }

  @Test
  void aFullExecutorQueueFailsTheJobItJustStarted() {
    IndexingJob job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(any(), any(), any(), any())).thenReturn(job);
    doThrow(new TaskRejectedException("voll")).when(target).refresh(any(), any(), any(), anyInt());
    enqueue("102");
    elapse();

    intake.drainDue();

    verify(indexingJobService).failJob(eq(job.getId()), any());
    assertThat(batchRows()).isZero();
  }

  @Test
  void aLibraryWhoseSecretWasRemovedMeanwhileIsDroppedSilently() {
    enqueue("102");
    library.setWebhookSecret(null);
    elapse();

    intake.drainDue();

    verifyNoInteractions(indexingJobService, executor);
    verify(target, never()).refresh(any(), any(), any(), anyInt());
    assertThat(batchRows()).isZero();
  }

  @Test
  void aFailedLookupAtDrainTimePutsTheBatchBackAsADeferral() {
    intake.enqueue(target, libraryId, Set.of("102", "103"), 1);
    when(libraryRepository.findById(libraryId))
        .thenThrow(new DataAccessResourceFailureException("Datenbank nicht erreichbar"))
        .thenReturn(Optional.of(library));
    elapse();

    intake.drainDue();

    assertThat(deferralsOfTheOpenBatch()).as("put back, not lost").containsExactly(1);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
    elapse();
    intake.drainDue();
    verify(target).refresh(any(), eq(library), eq(Set.of("102", "103")), eq(1));
  }

  @Test
  void aFailedLookupCountsAsADeferralAndEndsAtTheBound() {
    enqueue("102");
    when(libraryRepository.findById(libraryId))
        .thenThrow(new DataAccessResourceFailureException("Datenbank nicht erreichbar"));

    for (int drain = 0; drain < 3; drain++) {
      elapse();
      intake.drainDue();
    }

    assertThat(batchRows()).as("two deferrals, then dropped").isZero();
  }

  @Test
  void aLibraryGoneMeanwhileIsDroppedSilently() {
    enqueue("102");
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.empty());
    elapse();

    intake.drainDue();

    verifyNoInteractions(indexingJobService, executor);
    assertThat(batchRows()).isZero();
  }

  // ---- several instances (ADR-0053, Entscheidung 10)

  /**
   * The acceptance criterion: notifications for one library reaching two instances make one run per
   * debounce window, with every key, although both instances drain at the same moment.
   */
  @Test
  void notificationsOnTwoInstancesMakeOneRunWithEveryKey() throws Exception {
    SourceEventIntake instanceB =
        instance(new LibrarySourceConnectionResolver(), SourceEventIntake.LEASE);
    CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> intake.enqueue(target, libraryId, Set.of("101"), 0)),
            CompletableFuture.runAsync(
                () -> instanceB.enqueue(target, libraryId, Set.of("102"), 1)))
        .get();
    assertThat(batchRows()).as("one debounce window, one batch").isEqualTo(1);
    elapse();

    CompletableFuture.allOf(
            CompletableFuture.runAsync(intake::drainDue),
            CompletableFuture.runAsync(instanceB::drainDue))
        .get();

    verify(indexingJobService, times(1)).startJob(any(), any(), any(), any());
    verify(target, times(1)).refresh(any(), eq(library), eq(Set.of("101", "102")), eq(1));
    assertThat(batchRows()).isZero();
  }

  /** What one instance collected, another drains: nothing of a batch lives in a process. */
  @Test
  void aBatchCollectedByOneInstanceIsDrainedByAnotherAfterARestart() {
    enqueue("101", "102");
    SourceEventIntake restarted =
        instance(new LibrarySourceConnectionResolver(), SourceEventIntake.LEASE);
    elapse();

    restarted.drainDue();

    verify(target).refresh(any(), eq(library), eq(Set.of("101", "102")), eq(0));
  }

  /**
   * An instance claims a due batch and stops before it starts the run. Once its claim lapsed, any
   * instance drains the batch, and the keys reported meanwhile wait in the next batch.
   */
  @Test
  void aBatchClaimedByAnInstanceThatStoppedIsDrainedAfterItsLease() {
    enqueue("101");
    elapse();
    assertThat(batches.claimDue(List.of(PUSHED), Duration.ofSeconds(1))).isPresent();
    // the claiming instance is gone; a notification arriving now opens the next batch
    enqueue("102");
    intake.drainDue();
    verifyNoInteractions(indexingJobService);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              intake.drainDue();
              verify(target).refresh(any(), eq(library), eq(Set.of("101")), eq(0));
            });
    ArgumentCaptor<Set<String>> keys = ArgumentCaptor.forClass(Set.class);
    elapse();
    intake.drainDue();
    verify(target, times(2)).refresh(any(), eq(library), keys.capture(), anyInt());
    assertThat(keys.getAllValues()).containsExactly(Set.of("101"), Set.of("102"));
    assertThat(batchRows()).isZero();
  }
}
