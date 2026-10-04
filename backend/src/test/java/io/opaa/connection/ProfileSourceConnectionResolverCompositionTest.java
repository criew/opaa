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
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Run, change and the settings alone come from one composition, through a profile or not. */
class ProfileSourceConnectionResolverCompositionTest {

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final ProfileSourceConnectionResolver resolver =
      TestProfileResolvers.resolver(
          connections,
          profiles,
          TestProfileResolvers.blocks(
              mock(ConnectorTypePolicyRepository.class), connections, profiles, rows),
          rows);

  @Test
  void runChangeAndSettingsShareTheMergedConfigurationOfTheProfile() {
    KnowledgeLibrary library = library("nutzer:geheim", ProfileProbeSourceConnector.TYPE);
    library.updateSourceSettings("{\"edition\":\"CLOUD\",\"topic\":\"Wetter\"}");
    connectThroughProfile(
        library.getId(), ProfileProbeSourceConnector.TYPE, "{\"edition\":\"DC\"}");

    SourceSettings run = resolver.resolve(library);
    SourceSettings change = resolver.resolveForChange(library);
    ConnectorData settings = resolver.effectiveSettings(library);

    assertThat(run.connectorSettings())
        .isEqualTo(ConnectorData.of(Map.of("edition", "DC", "topic", "Wetter")))
        .isEqualTo(change.connectorSettings())
        .isEqualTo(settings);
    assertThat(change).isEqualTo(run);
    assertThat(run.sourceUrl()).isEqualTo("https://ablage.example.org/akten");
    assertThat(run.credentials().kind()).isEqualTo(SecretKind.PERSONAL_SECRET);
    assertThat(resolver.currentCredentials(library)).isEqualTo("nutzer:geheim");
    assertThat(resolver.storedCredentials(library)).isEqualTo("nutzer:geheim");
    assertThat(resolver.holdsCredentials(library)).isTrue();
  }

  /**
   * The run holds the library as loaded at its start; a secret discarded meanwhile (emergency
   * shutdown, new address) is no longer handed out, and the block names the profile.
   */
  @Test
  void aSecretDiscardedDuringTheRunIsNotHandedOutAgain() {
    KnowledgeLibrary row = library("nutzer:geheim");
    KnowledgeLibrary atRunStart = mock(KnowledgeLibrary.class);
    when(atRunStart.getId()).thenReturn(row.getId());
    when(atRunStart.getSourceType()).thenReturn(SourceTypes.RSS_FEED);
    when(atRunStart.getSourceUrl()).thenReturn(row.getSourceUrl());
    when(atRunStart.getSourceCredentials()).thenReturn("nutzer:geheim");
    connectThroughProfile(row.getId(), SourceTypes.RSS_FEED, null);

    assertThat(resolver.currentCredentials(atRunStart)).isEqualTo("nutzer:geheim");
    row.dropSourceCredentials();

    assertThatThrownBy(() -> resolver.currentCredentials(atRunStart))
        .isInstanceOf(SourceConnectionBlockedException.class)
        .hasMessageStartingWith("Verbindung getrennt: Für den Zugang \"Ablage\"")
        .satisfies(
            e ->
                assertThat(((SourceConnectionBlockedException) e).block().reason())
                    .isEqualTo(SourceBlock.Reason.NOT_CONNECTED));
  }

  @Test
  void aLibraryWithoutAProfileResolvesFromItsOwnFieldsAsBefore() {
    KnowledgeLibrary library = library("nutzer:geheim");
    library.updateSourceSettings("{\"region\":\"us\"}");
    when(connections.findById(library.getId())).thenReturn(Optional.empty());
    LibrarySourceConnectionResolver ownFields = new LibrarySourceConnectionResolver();

    assertThat(resolver.resolve(library)).isEqualTo(ownFields.resolve(library));
    assertThat(resolver.resolveForChange(library)).isEqualTo(ownFields.resolveForChange(library));
    assertThat(resolver.effectiveSettings(library)).isEqualTo(ownFields.effectiveSettings(library));
    assertThat(resolver.currentSecret(library)).isEqualTo(ownFields.currentSecret(library));
  }

  @Test
  void withoutAStoredSecretTheLibraryHoldsNone() {
    KnowledgeLibrary library = library(null);

    assertThat(resolver.storedCredentials(library)).isNull();
    assertThat(resolver.holdsCredentials(library)).isFalse();
  }

  private void connectThroughProfile(UUID libraryId, SourceType type, String defaults) {
    ConnectionProfile profile = mock(ConnectionProfile.class);
    UUID profileId = UUID.randomUUID();
    when(profile.getId()).thenReturn(profileId);
    when(profile.getSourceType()).thenReturn(type);
    when(profile.getName()).thenReturn("Ablage");
    when(profile.getServerUrl()).thenReturn("https://ablage.example.org");
    when(profile.getAuthMethod()).thenReturn(ConnectionAuthMethod.PERSONAL_SECRET);
    when(profile.getConnectorSettings()).thenReturn(defaults);
    LibraryConnection connection = new LibraryConnection(libraryId, profileId, Instant.EPOCH);
    when(connections.findById(libraryId)).thenReturn(Optional.of(connection));
    when(connections.findAllById(any())).thenReturn(List.of(connection));
    when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
    when(profiles.findAllById(any())).thenReturn(List.of(profile));
  }

  private KnowledgeLibrary library(String secret) {
    return library(secret, SourceTypes.RSS_FEED);
  }

  private KnowledgeLibrary library(String secret, SourceType type) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Akten",
            null,
            UUID.randomUUID(),
            type,
            null,
            "https://ablage.example.org/akten",
            null,
            secret,
            false);
    rows.put(library.getId(), library);
    return library;
  }
}
