package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.credentials.LibrarySourceConnectionResolver;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.run.SourceEventIntake;
import io.opaa.indexing.spi.PushIntakeHandler;
import io.opaa.indexing.spi.PushNotification;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.test.SourceTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The dispatch of the shared push intake: every request runs every registered intake's check once -
 * the library's own for real, the others against a stand-in - and an unknown library or one without
 * a push intake gets the same 401 after the same work. What an authenticated notification names is
 * queued for the library loaded here, never for one the connector names.
 */
class PushIntakeServiceTest {

  private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
  private static final UnaryOperator<String> HEADER = name -> "sha256=abcd";

  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final SourceConnectorRegistry connectors = mock(SourceConnectorRegistry.class);
  private final PushIntakeHandler confluence = mock(PushIntakeHandler.class);
  private final PushIntakeHandler s3 = mock(PushIntakeHandler.class);
  private final SourceEventIntake intake = mock(SourceEventIntake.class);
  private final PushIntakeService service =
      new PushIntakeService(libraries, connectors, new LibrarySourceConnectionResolver(), intake);

  {
    when(connectors.pushIntakeHandlers()).thenReturn(List.of(confluence, s3));
    when(connectors.pushIntakeHandler(SourceTypes.CONFLUENCE)).thenReturn(Optional.of(confluence));
    when(connectors.pushIntakeHandler(SourceTypes.S3)).thenReturn(Optional.of(s3));
    when(connectors.pushIntakeHandler(SourceType.UPLOAD)).thenReturn(Optional.empty());
  }

  @Test
  void anUnknownLibraryCostsEveryIntakesCheckAndIsA401() {
    UUID unknown = UUID.randomUUID();
    when(libraries.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.accept(unknown, BODY, HEADER))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(PushIntakeHandler.UNAUTHORIZED_MESSAGE);

    verify(confluence).rejectForeign(BODY, HEADER);
    verify(s3).rejectForeign(BODY, HEADER);
    verify(confluence, never()).acceptNotification(any(), any(), any(), any());
    verify(s3, never()).acceptNotification(any(), any(), any(), any());
    verify(libraries, times(1)).findById(unknown);
    verifyNoInteractions(intake);
  }

  @Test
  void aLibraryWithoutPushIntakeIsAnsweredLikeAnUnknownOne() {
    KnowledgeLibrary upload =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Ablage", null, UUID.randomUUID());
    when(libraries.findById(upload.getId())).thenReturn(Optional.of(upload));

    assertThatThrownBy(() -> service.accept(upload.getId(), BODY, HEADER))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(PushIntakeHandler.UNAUTHORIZED_MESSAGE);

    verify(confluence).rejectForeign(BODY, HEADER);
    verify(s3).rejectForeign(BODY, HEADER);
    verify(confluence, never()).acceptNotification(any(), any(), any(), any());
    verifyNoInteractions(intake);
  }

  @Test
  void aNotificationItsOwnIntakeRefusesQueuesNothing() {
    KnowledgeLibrary wiki = wiki();
    when(libraries.findById(wiki.getId())).thenReturn(Optional.of(wiki));
    when(confluence.acceptNotification(wiki, null, BODY, HEADER))
        .thenThrow(new UnauthorizedException(PushIntakeHandler.UNAUTHORIZED_MESSAGE));

    assertThatThrownBy(() -> service.accept(wiki.getId(), BODY, HEADER))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage(PushIntakeHandler.UNAUTHORIZED_MESSAGE);

    verify(s3).rejectForeign(BODY, HEADER);
    verifyNoInteractions(intake);
  }

  @Test
  void anIntakeAnsweringNullIsAContractBreachAndQueuesNothing() {
    KnowledgeLibrary wiki = wiki();
    when(libraries.findById(wiki.getId())).thenReturn(Optional.of(wiki));

    assertThatThrownBy(() -> service.accept(wiki.getId(), BODY, HEADER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageEndingWith("returned null");

    verifyNoInteractions(intake);
  }

  @Test
  void aLibrarysOwnIntakeChecksForRealAfterEveryOtherIntakesStandIn() {
    KnowledgeLibrary wiki = wiki();
    when(libraries.findById(wiki.getId())).thenReturn(Optional.of(wiki));

    PushNotification named = new PushNotification(Set.of("102"), 0);
    when(confluence.acceptNotification(wiki, null, BODY, HEADER)).thenReturn(named);

    service.accept(wiki.getId(), BODY, HEADER);

    InOrder order = inOrder(s3, confluence, intake);
    order.verify(s3).rejectForeign(BODY, HEADER);
    order.verify(confluence).acceptNotification(wiki, null, BODY, HEADER);
    order.verify(intake).enqueue(wiki, named);
    verify(libraries, times(1)).findById(wiki.getId());
    verify(confluence, never()).rejectForeign(any(), any());
  }

  private static KnowledgeLibrary wiki() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Wiki",
        null,
        UUID.randomUUID(),
        SourceTypes.CONFLUENCE,
        null,
        "https://wiki.example.org",
        "pat");
  }
}
