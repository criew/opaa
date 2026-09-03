package io.opaa.indexing.source.confluence.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.common.ConflictException;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.nio.charset.StandardCharsets;
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
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

/**
 * The intake's contract (#1140): one 401 for every way a request fails to authenticate, one
 * debounced run per library for any number of notifications, targeted fetches below the batch bound
 * and an incremental run above it, and a wait - then a drop - while another run is in progress.
 */
class ConfluenceWebhookServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
  private static final String SECRET = "geheimes-webhook-secret";

  private KnowledgeLibraryRepository libraryRepository;
  private IndexingJobService indexingJobService;
  private ConfluenceIndexingExecutor executor;
  private TaskScheduler scheduler;
  private final List<Runnable> scheduled = new ArrayList<>();
  private ConfluenceWebhookService service;
  private KnowledgeLibrary library;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    indexingJobService = mock(IndexingJobService.class);
    executor = mock(ConfluenceIndexingExecutor.class);
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
    library.configureConfluence(
        ConfluenceEdition.DATA_CENTER, List.of(new ConfluenceSpaceSelection("ENG", null)));
    library.setConfluenceWebhookSecret(SECRET);
    libraryId = UUID.randomUUID();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    IndexingJob job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(any(), any(), any(), any())).thenReturn(job);
    service =
        new ConfluenceWebhookService(
            libraryRepository,
            indexingJobService,
            executor,
            new ConfluenceWebhookProperties(Duration.ofSeconds(5), 3, 2),
            scheduler,
            JsonMapper.builder().build(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static byte[] body(String... pageIds) {
    StringBuilder json = new StringBuilder("{\"event\":\"page_updated\",\"pageIds\":[");
    for (int i = 0; i < pageIds.length; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(pageIds[i]).append('"');
    }
    return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
  }

  private void acceptSigned(byte[] body) {
    service.accept(libraryId, body, ConfluenceWebhookSignature.sign(body, SECRET), null);
  }

  @Test
  void rejectsEveryUnauthenticatedShapeWithTheSame401AndQueuesNothing() {
    byte[] body = body("102");
    String good = ConfluenceWebhookSignature.sign(body, SECRET);
    UUID unknown = UUID.randomUUID();
    when(libraryRepository.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.accept(libraryId, body, null, null))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(ConfluenceWebhookService.UNAUTHORIZED_MESSAGE);
    assertThatThrownBy(() -> service.accept(libraryId, body, "sha256=00", "falsch"))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(ConfluenceWebhookService.UNAUTHORIZED_MESSAGE);
    assertThatThrownBy(() -> service.accept(unknown, body, good, null))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(ConfluenceWebhookService.UNAUTHORIZED_MESSAGE);
    library.setConfluenceWebhookSecret(null);
    assertThatThrownBy(() -> service.accept(libraryId, body, good, SECRET))
        .as("no secret stored: nothing authenticates, not even the former secret")
        .isInstanceOf(UnauthorizedException.class);

    assertThat(scheduled).isEmpty();
    verifyNoInteractions(indexingJobService, executor);
  }

  @Test
  void collectsNotificationsForFiveSecondsAndStartsOneTargetedWebhookRun() {
    acceptSigned(body("102"));
    acceptSigned(body("103", "102"));
    service.accept(libraryId, body("104"), null, SECRET);

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
    verify(executor).refreshPages(any(), eq(library), eq(Set.of("102", "103", "104")));
    verify(executor, never()).execute(any(), any(), any());
  }

  @Test
  void aBodyNamingNoPageIsAcceptedButQueuesNothing() {
    byte[] body = "{\"event\":\"space_created\"}".getBytes(StandardCharsets.UTF_8);
    service.accept(libraryId, body, ConfluenceWebhookSignature.sign(body, SECRET), null);
    assertThat(scheduled).isEmpty();
    verifyNoInteractions(indexingJobService, executor);
  }

  @Test
  void aBatchBeyondTheBoundRunsAnOrdinaryIncrementalSyncInstead() {
    acceptSigned(body("1", "2", "3", "4"));
    scheduled.get(0).run();

    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.INCREMENTAL));
    verify(executor, never()).refreshPages(any(), any(), any());
  }

  @Test
  void waitsWhileARunIsInProgressAndDropsTheBatchAfterTheLastDeferral() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    acceptSigned(body("102"));

    scheduled.get(0).run();
    assertThat(scheduled).as("deferred once").hasSize(2);
    scheduled.get(1).run();
    assertThat(scheduled).as("deferred twice - the configured maximum").hasSize(3);
    scheduled.get(2).run();
    assertThat(scheduled).as("dropped, not rescheduled").hasSize(3);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());

    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(false);
    acceptSigned(body("102"));
    scheduled.get(3).run();
    verify(executor).refreshPages(any(), eq(library), eq(Set.of("102")));
  }

  @Test
  void aConflictAtStartIsTreatedLikeARunInProgress() {
    when(indexingJobService.startJob(any(), any(), any(), any()))
        .thenThrow(new ConflictException("läuft bereits"));
    acceptSigned(body("102"));

    scheduled.get(0).run();

    assertThat(scheduled).hasSize(2);
    verifyNoInteractions(executor);
  }

  @Test
  void aFullExecutorQueueFailsTheJobItJustStarted() {
    IndexingJob job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.startJob(any(), any(), any(), any())).thenReturn(job);
    org.mockito.Mockito.doThrow(new TaskRejectedException("voll"))
        .when(executor)
        .refreshPages(any(), any(), any());
    acceptSigned(body("102"));

    scheduled.get(0).run();

    verify(indexingJobService).failJob(eq(job.getId()), any());
  }

  @Test
  void aLibraryWhoseWebhookWasRemovedMeanwhileIsDroppedSilently() {
    acceptSigned(body("102"));
    library.setConfluenceWebhookSecret(null);

    scheduled.get(0).run();

    verifyNoInteractions(indexingJobService, executor);
  }
}
