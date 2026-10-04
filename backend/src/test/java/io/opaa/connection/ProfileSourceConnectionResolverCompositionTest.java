package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Run, change and the settings alone come from one composition, through a profile or not. */
class ProfileSourceConnectionResolverCompositionTest {

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final ProfileSourceConnectionResolver resolver =
      TestProfileResolvers.resolver(
          connections,
          profiles,
          TestProfileResolvers.blocks(
              mock(ConnectorTypePolicyRepository.class), connections, profiles));

  @Test
  void runChangeAndSettingsShareTheMergedConfigurationOfTheProfile() {
    KnowledgeLibrary library = library("nutzer:geheim");
    library.updateSourceSettings("{\"region\":\"us\",\"bucket\":\"akten\"}");
    ConnectionProfile profile = mock(ConnectionProfile.class);
    UUID profileId = UUID.randomUUID();
    when(profile.getId()).thenReturn(profileId);
    when(profile.getName()).thenReturn("Ablage");
    when(profile.getServerUrl()).thenReturn("https://ablage.example.org");
    when(profile.getAuthMethod()).thenReturn(ConnectionAuthMethod.PERSONAL_SECRET);
    when(profile.getConnectorSettings()).thenReturn("{\"region\":\"eu\",\"pathStyle\":true}");
    LibraryConnection connection = new LibraryConnection(library.getId(), profileId, Instant.EPOCH);
    when(connections.findById(library.getId())).thenReturn(Optional.of(connection));
    when(connections.findAllById(any())).thenReturn(List.of(connection));
    when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
    when(profiles.findAllById(any())).thenReturn(List.of(profile));

    SourceSettings run = resolver.resolve(library);
    SourceSettings change = resolver.resolveForChange(library);
    ConnectorData settings = resolver.effectiveSettings(library);

    assertThat(run.connectorSettings())
        .isEqualTo(ConnectorData.of(Map.of("region", "eu", "bucket", "akten", "pathStyle", true)))
        .isEqualTo(change.connectorSettings())
        .isEqualTo(settings);
    assertThat(change).isEqualTo(run);
    assertThat(run.sourceUrl()).isEqualTo("https://ablage.example.org/akten");
    assertThat(run.credentials().kind()).isEqualTo(SecretKind.PERSONAL_SECRET);
    assertThat(resolver.currentCredentials(library)).isEqualTo("nutzer:geheim");
    assertThat(resolver.storedCredentials(library)).isEqualTo("nutzer:geheim");
    assertThat(resolver.holdsCredentials(library)).isTrue();
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

  private static KnowledgeLibrary library(String secret) {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Akten",
        null,
        UUID.randomUUID(),
        SourceTypes.RSS_FEED,
        null,
        "https://ablage.example.org/akten",
        null,
        secret,
        false);
  }
}
