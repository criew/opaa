package io.opaa.indexing.source.confluence.webhook;

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

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.SourceEventIntake;
import io.opaa.indexing.source.SourceEventTarget;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
import io.opaa.library.ConfluenceSpaceSelection;
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
 * The adapter's contract: one 401 for every way a request fails to authenticate, the page ids the
 * body names handed to the shared intake, and the targeted run delegated to the executor's page
 * refresh. Collecting, overflow and deferral are the intake's own contract.
 */
class ConfluenceWebhookServiceTest {

  private static final String SECRET = "geheimes-webhook-secret";

  private KnowledgeLibraryRepository libraryRepository;
  private ConfluenceIndexingExecutor executor;
  private SourceEventIntake intake;
  private ConfluenceWebhookService service;
  private KnowledgeLibrary library;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    executor = mock(ConfluenceIndexingExecutor.class);
    intake = mock(SourceEventIntake.class);
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
    library.setWebhookSecret(SECRET);
    libraryId = UUID.randomUUID();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    service =
        new ConfluenceWebhookService(
            libraryRepository, executor, intake, JsonMapper.builder().build());
  }

  private static byte[] body(String... pageIds) {
    StringBuilder json = new StringBuilder("{\"event\":\"page_updated\",\"pageIds\":[");
    for (int i = 0; i < pageIds.length; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(pageIds[i]).append('"');
    }
    return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
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
    library.setWebhookSecret(null);
    assertThatThrownBy(() -> service.accept(libraryId, body, good, SECRET))
        .as("no secret stored: nothing authenticates, not even the former secret")
        .isInstanceOf(UnauthorizedException.class);

    verifyNoInteractions(intake, executor);
  }

  @Test
  void aLibraryOfAnotherSourceTypeDoesNotAuthenticate() {
    KnowledgeLibrary s3 =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Objekte",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            "https://minio.example.org",
            null,
            "AKIA:geheim",
            false);
    s3.setWebhookSecret(SECRET);
    UUID s3Id = UUID.randomUUID();
    when(libraryRepository.findById(s3Id)).thenReturn(Optional.of(s3));
    byte[] body = body("102");

    assertThatThrownBy(
            () -> service.accept(s3Id, body, ConfluenceWebhookSignature.sign(body, SECRET), null))
        .isInstanceOf(UnauthorizedException.class);
    verifyNoInteractions(intake);
  }

  @Test
  void handsTheNamedPagesToTheIntakeWhetherSignedOrSentWithTheSharedSecret() {
    byte[] signed = body("102", "103");
    service.accept(libraryId, signed, ConfluenceWebhookSignature.sign(signed, SECRET), null);
    service.accept(libraryId, body("104"), null, SECRET);

    ArgumentCaptor<SourceEventTarget> target = ArgumentCaptor.forClass(SourceEventTarget.class);
    verify(intake).enqueue(target.capture(), eq(libraryId), eq(Set.of("102", "103")), eq(0));
    verify(intake).enqueue(any(), eq(libraryId), eq(Set.of("104")), eq(0));
    assertThat(target.getValue().sourceType()).isEqualTo(DocumentSourceType.CONFLUENCE);
    assertThat(target.getValue().targetedRunMode()).isEqualTo(IndexingRunMode.INCREMENTAL);
    assertThat(target.getValue().executor()).isSameAs(executor);
  }

  @Test
  void aBodyNamingNoPageIsAcceptedButQueuesNothing() {
    byte[] body = "{\"event\":\"space_created\"}".getBytes(StandardCharsets.UTF_8);
    service.accept(libraryId, body, ConfluenceWebhookSignature.sign(body, SECRET), null);
    verifyNoInteractions(intake, executor);
  }

  @Test
  void theTargetedRunFetchesExactlyTheCollectedPages() {
    byte[] body = body("102");
    service.accept(libraryId, body, ConfluenceWebhookSignature.sign(body, SECRET), null);
    ArgumentCaptor<SourceEventTarget> target = ArgumentCaptor.forClass(SourceEventTarget.class);
    verify(intake).enqueue(target.capture(), any(), any(), anyInt());
    UUID jobId = UUID.randomUUID();

    target.getValue().refresh(jobId, library, Set.of("102", "103"), 0);

    verify(executor).refreshPages(jobId, library, Set.of("102", "103"));
    verify(executor, never()).execute(any(), any(), any());
  }
}
