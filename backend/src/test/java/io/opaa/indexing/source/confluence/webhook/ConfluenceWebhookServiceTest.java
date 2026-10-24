package io.opaa.indexing.source.confluence.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.run.SourceEventIntake;
import io.opaa.indexing.run.SourceEventTarget;
import io.opaa.indexing.source.confluence.ConfluenceEdition;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
import io.opaa.indexing.source.confluence.ConfluenceSpaceSelection;
import io.opaa.indexing.source.confluence.ConfluenceTestSettings;
import io.opaa.indexing.source.confluence.ConfluenceWebhookSignature;
import io.opaa.indexing.spi.PushNotification;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.SourceTypes;
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
 * body names returned to the core for the shared intake, and the targeted run delegated to the
 * executor's page refresh. Collecting, overflow and deferral are the intake's own contract.
 */
class ConfluenceWebhookServiceTest {

  private static final String SECRET = "geheimes-webhook-secret";

  private KnowledgeLibraryRepository libraryRepository;
  private ConfluenceIndexingExecutor executor;
  private SourceEventIntake intake;
  private ConfluenceWebhookService service;
  private SourceEventTarget target;
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
            SourceTypes.CONFLUENCE,
            null,
            "https://wiki.example.org",
            "token");
    ConfluenceTestSettings.configure(
        library, ConfluenceEdition.DATA_CENTER, List.of(new ConfluenceSpaceSelection("ENG", null)));
    library.setWebhookSecret(SECRET);
    libraryId = UUID.randomUUID();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    service =
        new ConfluenceWebhookService(
            libraryRepository, executor, intake, JsonMapper.builder().build());
    // the target is registered once, at creation, so any instance drains this connector's batches
    ArgumentCaptor<SourceEventTarget> registered = ArgumentCaptor.forClass(SourceEventTarget.class);
    verify(intake).register(registered.capture());
    target = registered.getValue();
    org.mockito.Mockito.clearInvocations(intake);
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
            SourceTypes.S3,
            null,
            "https://minio.example.org",
            "AKIA:geheim");
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
  void returnsTheNamedPagesWhetherSignedOrSentWithTheSharedSecret() {
    byte[] signed = body("102", "103");
    assertThat(
            service.accept(
                libraryId, signed, ConfluenceWebhookSignature.sign(signed, SECRET), null))
        .isEqualTo(new PushNotification(Set.of("102", "103"), 0));
    assertThat(service.accept(libraryId, body("104"), null, SECRET))
        .isEqualTo(new PushNotification(Set.of("104"), 0));

    verifyNoInteractions(intake);
    assertThat(target.sourceType()).isEqualTo(SourceTypes.CONFLUENCE);
    assertThat(target.targetedRunMode()).isEqualTo(IndexingRunMode.INCREMENTAL);
    assertThat(target.executor()).isSameAs(executor);
  }

  @Test
  void aBodyNamingNoPageIsAcceptedButQueuesNothing() {
    byte[] body = "{\"event\":\"space_created\"}".getBytes(StandardCharsets.UTF_8);
    assertThat(service.accept(libraryId, body, ConfluenceWebhookSignature.sign(body, SECRET), null))
        .isEqualTo(PushNotification.none());
    verifyNoInteractions(intake, executor);
  }

  @Test
  void theTargetedRunFetchesExactlyTheCollectedPages() {
    UUID jobId = UUID.randomUUID();

    target.refresh(jobId, library, Set.of("102", "103"), 0);

    verify(executor).refreshPages(jobId, library, Set.of("102", "103"));
    verify(executor, never()).execute(any(), any(), any());
  }
}
