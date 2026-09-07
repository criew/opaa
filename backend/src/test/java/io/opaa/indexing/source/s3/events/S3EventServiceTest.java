package io.opaa.indexing.source.s3.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.SourceEventIntake;
import io.opaa.indexing.source.SourceEventTarget;
import io.opaa.indexing.source.s3.S3IndexingExecutor;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter's contract (ADR-0027, Entscheidung 6): one 401 for every way a request fails to
 * authenticate, events outside the scopes dropped and counted, the admitted keys handed to the
 * shared intake, and the targeted run delegated to the executor's object refresh. Collecting,
 * overflow and deferral are the intake's own contract.
 */
class S3EventServiceTest {

  private static final String TOKEN = "ereignis-token";

  private KnowledgeLibraryRepository libraryRepository;
  private S3IndexingExecutor executor;
  private SourceEventIntake intake;
  private S3EventService service;
  private KnowledgeLibrary library;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    executor = mock(S3IndexingExecutor.class);
    intake = mock(SourceEventIntake.class);
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
    service = new S3EventService(libraryRepository, executor, intake, JsonMapper.builder().build());
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
    verifyNoInteractions(intake, executor);
  }

  @Test
  void aLibraryOfAnotherSourceTypeDoesNotAuthenticate() {
    KnowledgeLibrary confluence =
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
    confluence.setWebhookSecret(TOKEN);
    UUID confluenceId = UUID.randomUUID();
    when(libraryRepository.findById(confluenceId)).thenReturn(Optional.of(confluence));

    assertThatThrownBy(
            () ->
                service.accept(
                    confluenceId, records("dokumente/2025/a.pdf"), "Bearer " + TOKEN, null))
        .isInstanceOf(UnauthorizedException.class);
    verifyNoInteractions(intake);
  }

  @Test
  void handsTheAdmittedKeysToTheIntakeAndCountsWhatLiesOutside() {
    acceptWithBearer(records("dokumente/2025/a.pdf", "fremd/x.pdf", "dokumente/2024/alt.pdf"));
    service.accept(
        libraryId, records("satzungen/haupt.txt", "dokumente/2025/entwurf-b.pdf"), null, TOKEN);

    ArgumentCaptor<SourceEventTarget> target = ArgumentCaptor.forClass(SourceEventTarget.class);
    verify(intake)
        .enqueue(target.capture(), eq(libraryId), eq(Set.of("dokumente/2025/a.pdf")), eq(2));
    verify(intake).enqueue(any(), eq(libraryId), eq(Set.of("satzungen/haupt.txt")), eq(1));
    assertThat(target.getValue().sourceType()).isEqualTo(DocumentSourceType.S3);
    assertThat(target.getValue().targetedRunMode()).isEqualTo(IndexingRunMode.EVENT);
    assertThat(target.getValue().executor()).isSameAs(executor);
  }

  @Test
  void theSetUpTestEventAndAnAllOutsideBatchQueueNothing() {
    service.accept(
        libraryId, "{\"Event\":\"s3:TestEvent\"}".getBytes(StandardCharsets.UTF_8), null, TOKEN);
    acceptWithBearer(records("fremd/x.pdf"));

    verifyNoInteractions(intake, executor);
  }

  @Test
  void aLibraryWithoutSettingsAdmitsNothing() {
    KnowledgeLibrary unconfigured =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Leer",
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
    unconfigured.setWebhookSecret(TOKEN);
    UUID unconfiguredId = UUID.randomUUID();
    when(libraryRepository.findById(unconfiguredId)).thenReturn(Optional.of(unconfigured));

    service.accept(unconfiguredId, records("dokumente/2025/a.pdf"), "Bearer " + TOKEN, null);

    verifyNoInteractions(intake);
  }

  @Test
  void theTargetedRunChecksExactlyTheCollectedObjectsWithTheDroppedCount() {
    acceptWithBearer(records("dokumente/2025/a.pdf"));
    ArgumentCaptor<SourceEventTarget> target = ArgumentCaptor.forClass(SourceEventTarget.class);
    verify(intake).enqueue(target.capture(), any(), any(), anyInt());
    UUID jobId = UUID.randomUUID();

    target.getValue().refresh(jobId, library, Set.of("dokumente/2025/a.pdf"), 3);

    verify(executor).refreshObjects(jobId, library, Set.of("dokumente/2025/a.pdf"), 3);
    verify(executor, never()).execute(any(), any(), any());
  }
}
