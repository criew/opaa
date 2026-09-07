package io.opaa.indexing.source.s3.events;

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

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.common.ConflictException;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.indexing.source.s3.S3IndexingExecutor;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
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
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

/**
 * The intake's contract (ADR-0027, Entscheidung 6): one 401 for every way a request fails to
 * authenticate, events outside the scopes dropped and counted, one debounced EVENT run per library
 * for any number of notifications, a full sync above the batch bound, and a wait - then a drop -
 * while another run is in progress.
 */
class S3EventServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-06T21:00:00Z");
  private static final String TOKEN = "ereignis-token";

  private KnowledgeLibraryRepository libraryRepository;
  private IndexingJobService indexingJobService;
  private S3IndexingExecutor executor;
  private final List<Runnable> scheduled = new ArrayList<>();
  private S3EventService service;
  private KnowledgeLibrary library;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    indexingJobService = mock(IndexingJobService.class);
    executor = mock(S3IndexingExecutor.class);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
        .thenAnswer(
            inv -> {
              scheduled.add(inv.getArgument(0));
              return null;
            });
    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Protokolle",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            "https://minio.intern.example:9000",
            null,
            "AKIA:geheim",
            false);
    library.updateS3Settings(
        new S3SourceSettings(
            null,
            true,
            List.of(S3Scope.of("dokumente", "2025/"), S3Scope.of("satzungen", "")),
            null,
            List.of("**/entwurf-*")));
    library.setWebhookSecret(TOKEN);
    libraryId = UUID.randomUUID();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(indexingJobService.startJob(any(), any(), any(), any()))
        .thenReturn(new IndexingJob(JobStatus.RUNNING));
    service =
        new S3EventService(
            libraryRepository,
            indexingJobService,
            executor,
            new S3EventProperties(Duration.ofSeconds(5), 3, 2),
            scheduler,
            JsonMapper.builder().build(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static byte[] records(String... bucketAndKeys) {
    StringBuilder json = new StringBuilder("{\"Records\":[");
    for (int i = 0; i < bucketAndKeys.length; i++) {
      String[] parts = bucketAndKeys[i].split("/", 2);
      json.append(i == 0 ? "" : ",")
          .append("{\"eventName\":\"s3:ObjectCreated:Put\",\"s3\":{\"bucket\":{\"name\":\"")
          .append(parts[0])
          .append("\"},\"object\":{\"key\":\"")
          .append(parts[1])
          .append("\"}}}");
    }
    return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
  }

  private void acceptWithBearer(byte[] body) {
    service.accept(libraryId, body, "Bearer " + TOKEN, null);
  }

  @Test
  void rejectsEveryUnauthenticatedShapeWithTheSame401AndQueuesNothing() {
    byte[] body = records("dokumente/2025/a.pdf");
    UUID unknown = UUID.randomUUID();
    when(libraryRepository.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.accept(libraryId, body, null, null))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(S3EventService.UNAUTHORIZED_MESSAGE);
    assertThatThrownBy(() -> service.accept(libraryId, body, "Bearer falsch", "falsch"))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(S3EventService.UNAUTHORIZED_MESSAGE);
    assertThatThrownBy(() -> service.accept(unknown, body, "Bearer " + TOKEN, null))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(S3EventService.UNAUTHORIZED_MESSAGE);
    library.setWebhookSecret(null);
    assertThatThrownBy(() -> service.accept(libraryId, body, "Bearer " + TOKEN, TOKEN))
        .as("no token stored: nothing authenticates, not even the former token")
        .isInstanceOf(UnauthorizedException.class);
    assertThat(scheduled).isEmpty();
    verifyNoInteractions(executor);
  }

  @Test
  void queuesTheReportedKeysOnceDebouncedIntoOneEventRunAndDropsWhatLiesOutside() {
    acceptWithBearer(records("dokumente/2025/a.pdf", "fremd/x.pdf", "dokumente/2024/alt.pdf"));
    service.accept(
        libraryId, records("satzungen/haupt.txt", "dokumente/2025/entwurf-b.pdf"), null, TOKEN);
    service.accept(
        libraryId, "{\"Event\":\"s3:TestEvent\"}".getBytes(StandardCharsets.UTF_8), null, TOKEN);

    assertThat(scheduled).as("one timer per library, however many notifications").hasSize(1);
    verifyNoInteractions(executor);
    scheduled.get(0).run();

    verify(indexingJobService)
        .startJob(
            library.getId(),
            library.getOrganizationId(),
            JobTriggerSource.WEBHOOK,
            IndexingRunMode.EVENT);
    verify(executor)
        .refreshObjects(
            any(), eq(library), eq(Set.of("dokumente/2025/a.pdf", "satzungen/haupt.txt")), eq(3));
    verify(executor, never()).execute(any(), any(), any());
  }

  @Test
  void aBatchPastTheBoundBecomesAFullSyncAndAnAllOutsideBatchStartsNothing() {
    acceptWithBearer(records("dokumente/2025/a.pdf", "dokumente/2025/b.pdf"));
    acceptWithBearer(records("dokumente/2025/c.pdf", "dokumente/2025/d.pdf"));
    scheduled.get(0).run();

    verify(indexingJobService)
        .startJob(
            library.getId(),
            library.getOrganizationId(),
            JobTriggerSource.WEBHOOK,
            IndexingRunMode.FULL);
    verify(executor).execute(any(), eq(library), eq(IndexingRunMode.FULL));
    verify(executor, never())
        .refreshObjects(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());

    scheduled.clear();
    acceptWithBearer(records("fremd/x.pdf"));
    assertThat(scheduled).as("nothing inside the scopes: no timer, no run").isEmpty();
    verify(indexingJobService, times(1)).startJob(any(), any(), any(), any());
  }

  @Test
  void waitsWhileARunIsInProgressAndDropsTheBatchAfterTheLastDeferral() {
    when(indexingJobService.isJobRunning(library.getId(), library.getOrganizationId()))
        .thenReturn(true);
    acceptWithBearer(records("dokumente/2025/a.pdf"));

    scheduled.get(0).run();
    assertThat(scheduled).as("deferred once").hasSize(2);
    scheduled.get(1).run();
    assertThat(scheduled).as("deferred twice - the bound").hasSize(3);
    scheduled.get(2).run();
    assertThat(scheduled).as("dropped: no further timer").hasSize(3);
    verify(indexingJobService, never()).startJob(any(), any(), any(), any());

    // a conflict at start time defers the same way
    when(indexingJobService.isJobRunning(any(), any())).thenReturn(false);
    when(indexingJobService.startJob(any(), any(), any(), any()))
        .thenThrow(new ConflictException("läuft"));
    scheduled.clear();
    acceptWithBearer(records("dokumente/2025/b.pdf"));
    scheduled.get(0).run();
    assertThat(scheduled).hasSize(2);
    verifyNoInteractions(executor);
  }

  @Test
  void aBatchWhoseTokenWasRemovedMeanwhileIsDropped() {
    acceptWithBearer(records("dokumente/2025/a.pdf"));
    library.setWebhookSecret(null);

    scheduled.get(0).run();

    verify(indexingJobService, never()).startJob(any(), any(), any(), any());
    verifyNoInteractions(executor);
  }
}
