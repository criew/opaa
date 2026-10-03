package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A lock stops the start of a run and the fetch of an original ({@code resolve}), but not the
 * secret a run already going asks for again ({@code currentCredentials}): such a run ends regularly
 * (spec "Konnektor-Freigabe und Sperre").
 */
class ProfileSourceConnectionResolverLockTest {

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectorLockService locks = mock(ConnectorLockService.class);
  private final ProfileSourceConnectionResolver resolver =
      new ProfileSourceConnectionResolver(
          connections, mock(ConnectionProfileRepository.class), locks);

  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Feed",
          null,
          UUID.randomUUID(),
          SourceTypes.RSS_FEED,
          null,
          "https://feeds.example.org/a.xml",
          null,
          "nutzer:geheim",
          false);

  @Test
  void aLockBlocksTheStartButNotTheRunAlreadyGoing() {
    when(connections.findById(library.getId())).thenReturn(Optional.empty());
    when(locks.lockNotice(library))
        .thenReturn(Optional.of("Gesperrt – Inhalt wird nicht mehr aktualisiert."));

    assertThatThrownBy(() -> resolver.resolve(library))
        .isInstanceOf(SourceConnectionBlockedException.class)
        .satisfies(
            blocked ->
                assertThat(((SourceConnectionBlockedException) blocked).category())
                    .isEqualTo(SourceConnectionBlockedException.Category.LOCKED))
        .hasMessageStartingWith("Gesperrt");
    assertThat(resolver.currentCredentials(library)).isEqualTo("nutzer:geheim");
  }

  @Test
  void withoutALockALibraryWithItsOwnAddressResolvesAsBefore() {
    when(connections.findById(library.getId())).thenReturn(Optional.empty());
    when(locks.lockNotice(library)).thenReturn(Optional.empty());

    assertThat(resolver.resolve(library).sourceUrl()).isEqualTo("https://feeds.example.org/a.xml");
  }
}
