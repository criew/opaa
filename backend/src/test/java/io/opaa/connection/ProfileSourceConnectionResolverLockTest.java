package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorTypePolicy;
import io.opaa.connection.profile.ConnectorTypePolicyRepository;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.ProfileRequirements;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.SourceTypes;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
      new ProfileSourceConnectionResolver(
          connections,
          profiles,
          registry(),
          new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()),
          new SourceBlocks(
              policies,
              connections,
              profiles,
              new ProfileRequirements(policies, registry()),
              registry()));

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SourceConnectorRegistry> registry() {
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(TestSourceConnectors.connectors().registry());
    return provider;
  }

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
