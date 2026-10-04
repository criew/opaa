package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorTypePolicyRepository;
import io.opaa.connection.profile.LibraryConnection;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A library whose address left its profile and which holds no secret: a run is refused for the
 * address, while an answer marks the source as not connected, because it shows no address reason.
 */
class ConnectionSourceStateLookupTest {

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final SourceBlocks blocks =
      new SourceBlocks(
          mock(ConnectorTypePolicyRepository.class),
          connections,
          profiles,
          new ProfileRequirements(mock(ConnectorTypePolicyRepository.class), registry()),
          registry());

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SourceConnectorRegistry> registry() {
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(TestSourceConnectors.connectors().registry());
    return provider;
  }

  @Test
  void aRunNamesTheAddressAndAnAnswerTheMissingSecret() {
    KnowledgeLibrary outside = libraryAt("https://elsewhere.example.org/a.xml", null);
    KnowledgeLibrary inside = libraryAt("https://feeds.example.org/a.xml", "nutzer:geheim");
    ConnectionProfile profile = mock(ConnectionProfile.class);
    UUID profileId = UUID.randomUUID();
    when(profile.getId()).thenReturn(profileId);
    when(profile.getName()).thenReturn("Feeds");
    when(profile.getServerUrl()).thenReturn("https://feeds.example.org");
    when(profile.getAuthMethod()).thenReturn(ConnectionAuthMethod.PERSONAL_SECRET);
    when(connections.findAllById(any()))
        .thenReturn(
            List.of(
                new LibraryConnection(outside.getId(), profileId, Instant.EPOCH),
                new LibraryConnection(inside.getId(), profileId, Instant.EPOCH)));
    when(profiles.findAllById(any())).thenReturn(List.of(profile));
    ProfileSourceConnectionResolver resolver =
        new ProfileSourceConnectionResolver(
            connections,
            profiles,
            registry(),
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()),
            blocks);

    assertThatThrownBy(() -> resolver.resolve(outside))
        .isInstanceOf(SourceConnectionBlockedException.class)
        .satisfies(
            e ->
                assertThat(((SourceConnectionBlockedException) e).block().reason())
                    .isEqualTo(SourceBlock.Reason.TARGET_OUTSIDE_PROFILE));
    assertThat(new ConnectionSourceStateLookup(blocks).frozenAmong(List.of(outside, inside)))
        .containsOnlyKeys(outside.getId())
        .extractingByKey(outside.getId())
        .satisfies(
            block -> {
              assertThat(block.reason()).isEqualTo(SourceBlock.Reason.NOT_CONNECTED);
              assertThat(block.responsible()).isEqualTo("Verwaltende der Bibliothek");
            });
  }

  private static KnowledgeLibrary libraryAt(String url, String secret) {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Feed",
        null,
        UUID.randomUUID(),
        SourceTypes.RSS_FEED,
        null,
        url,
        null,
        secret,
        false);
  }
}
