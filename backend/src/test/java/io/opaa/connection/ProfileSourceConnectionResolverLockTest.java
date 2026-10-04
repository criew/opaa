package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorTypePolicy;
import io.opaa.connection.profile.ConnectorTypePolicyRepository;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A lock stops the start of a run and the fetch of an original ({@code resolve}), but not the
 * secret a run already going asks for again ({@code currentCredentials}): such a run ends regularly
 * (spec "Konnektor-Freigabe und Sperre").
 */
class ProfileSourceConnectionResolverLockTest {

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final ConnectorTypePolicyRepository policies = mock(ConnectorTypePolicyRepository.class);
  private final ProfileSourceConnectionResolver resolver =
      TestProfileResolvers.resolver(
          connections, profiles, TestProfileResolvers.blocks(policies, connections, profiles));

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
    ConnectorTypePolicy lockedType = mock(ConnectorTypePolicy.class);
    when(lockedType.getSourceType()).thenReturn(SourceTypes.RSS_FEED);
    when(lockedType.isLocked()).thenReturn(true);
    when(policies.findAll()).thenReturn(List.of(lockedType));

    assertThatThrownBy(() -> resolver.resolve(library))
        .isInstanceOf(SourceConnectionBlockedException.class)
        .satisfies(
            blocked ->
                assertThat(((SourceConnectionBlockedException) blocked).block().reason())
                    .isEqualTo(SourceBlock.Reason.TYPE_LOCKED))
        .hasMessageStartingWith("Gesperrt");
    assertThat(resolver.currentCredentials(library)).isEqualTo("nutzer:geheim");
  }

  @Test
  void withoutALockALibraryWithItsOwnAddressResolvesAsBefore() {
    when(policies.findAll()).thenReturn(List.of());

    assertThat(resolver.resolve(library).sourceUrl()).isEqualTo("https://feeds.example.org/a.xml");
  }
}
