package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.common.ValidationException;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.LibraryRows;
import io.opaa.connection.profile.ProfileRequirementService;
import io.opaa.connection.profile.ProfileRequirements;
import io.opaa.connection.profile.TransitionWiring;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.upload.UploadSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.UploadedOriginalStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Connecting and releasing a saved library pass its connector: the secret stays only while its
 * target (origin and the connector's binding) stays, a refusal changes nothing, an address may be
 * given, and releasing makes what the profile set the library's own.
 */
class LibraryConnectionServiceConnectTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);

  @Test
  void aMoveWithinTheOriginDiscardsTheSecretWhenTheConnectorBindsItNarrower() {
    KnowledgeLibrary library = connectedLibrary("{\"topic\": \"t\"}");

    service(new ShareBoundProbe())
        .connect(library, profile("https://probe.example.org/b", null).getId(), null);

    assertThat(library.getSourceUrl()).isEqualTo("https://probe.example.org/b/x");
    verify(libraries).eraseSourceCredentials(library.getId());
  }

  @Test
  void aMoveWithinTheOriginKeepsTheSecretWhereOnlyTheOriginCounts() {
    KnowledgeLibrary library = connectedLibrary("{\"topic\": \"t\"}");

    service(new ProfileProbeSourceConnector())
        .connect(library, profile("https://probe.example.org/b", null).getId(), null);

    assertThat(library.getSourceUrl()).isEqualTo("https://probe.example.org/b/x");
    verify(libraries, never()).eraseSourceCredentials(any());
  }

  @Test
  void aChangedDefaultAsksTheConnectorWithTheEffectiveConfigurationBeforeAndAfter() {
    KnowledgeLibrary library = connectedLibrary("{\"topic\": \"t\"}");
    ProfileProbeSourceConnector probe = new ProfileProbeSourceConnector();

    service(probe)
        .connect(
            library, profile("https://probe.example.org/a", "{\"edition\": \"DC\"}").getId(), null);

    ProfileProbeSourceConnector.ChangeCheck check = probe.changeChecks().getFirst();
    assertThat(check.stored().connectorSettings().asMap()).isEqualTo(Map.of("topic", "t"));
    assertThat(check.requested().connectorSettings().asMap())
        .isEqualTo(Map.of("topic", "t", "edition", "DC"));
    assertThat(check.requested().sourceCredentials()).isEqualTo("nutzer:geheim");
    assertThat(probe.sourceChanges())
        .singleElement()
        .satisfies(change -> assertThat(change.changedSettings()).containsExactly("edition"));
  }

  /** A default the new profile does not set stays with the library as its own value. */
  @Test
  void aSwitchToAProfileWithoutThePreviousDefaultKeepsItsValueAsTheLibrarysOwn() {
    KnowledgeLibrary library = connectedLibrary("{\"topic\": \"t\"}");
    ConnectionProfile current = profiles.findById(currentProfileOf(library)).orElseThrow();
    ReflectionTestUtils.setField(current, "connectorSettings", "{\"edition\": \"DC\"}");
    ProfileProbeSourceConnector probe = new ProfileProbeSourceConnector();

    service(probe).connect(library, profile("https://probe.example.org/a", null).getId(), null);

    assertThat(ConnectorData.storedIn(library).asMap())
        .isEqualTo(Map.of("topic", "t", "edition", "DC"));
    assertThat(probe.changeChecks()).as("the effective configuration stays").isEmpty();
    verify(connections).save(any());
  }

  @Test
  void aDefaultTheConnectorRefusesLeavesTheLibraryAndItsConnectionAsTheyWere() {
    KnowledgeLibrary library =
        connectedLibrary("{\"topic\": \"" + ProfileProbeSourceConnector.CLOUD_ONLY_TOPIC + "\"}");
    UUID target = profile("https://probe.example.org/b", "{\"edition\": \"DC\"}").getId();

    assertThatThrownBy(
            () -> service(new ProfileProbeSourceConnector()).connect(library, target, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur in der Edition CLOUD");

    assertThat(library.getSourceUrl()).isEqualTo("https://probe.example.org/a/x");
    assertThat(library.getSourceSettings()).contains(ProfileProbeSourceConnector.CLOUD_ONLY_TOPIC);
    verify(connections, never()).save(any());
    verify(libraries, never()).eraseSourceCredentials(any());
  }

  @Test
  void aGivenAddressUnderTheProfileIsTakenAndOneOutsideIsRefused() {
    KnowledgeLibrary library = ownLibrary("https://eigen.example.org/x");
    UUID target = profile("https://probe.example.org", null).getId();
    LibraryConnectionService service = service(new ProfileProbeSourceConnector());

    assertThatThrownBy(() -> service.connect(library, target, "https://anders.example.org/x"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Server-Adresse des Zugangs");
    service.connect(library, target, "https://probe.example.org/neu");

    assertThat(library.getSourceUrl()).isEqualTo("https://probe.example.org/neu");
    verify(libraries).eraseSourceCredentials(library.getId());
  }

  @Test
  void releasingMakesTheDefaultsProxyAndTlsSwitchOfTheProfileTheLibrarysOwn() {
    KnowledgeLibrary library = connectedLibrary("{\"topic\": \"t\"}");
    ConnectionProfile current = profiles.findById(currentProfileOf(library)).orElseThrow();
    ReflectionTestUtils.setField(current, "connectorSettings", "{\"edition\": \"DC\"}");
    ReflectionTestUtils.setField(current, "sourceProxy", "proxy.example.org:3128");
    ReflectionTestUtils.setField(current, "sourceInsecureSsl", true);
    ProfileProbeSourceConnector probe = new ProfileProbeSourceConnector();

    service(probe).disconnect(library);

    assertThat(ConnectorData.storedIn(library).asMap())
        .isEqualTo(Map.of("topic", "t", "edition", "DC"));
    assertThat(library.getSourceProxy()).isEqualTo("proxy.example.org:3128");
    assertThat(library.isSourceInsecureSsl()).isTrue();
    assertThat(library.getSourceUrl()).isEqualTo("https://probe.example.org/a/x");
    verify(libraries, never()).eraseSourceCredentials(any());
    verify(connections).delete(any());
    assertThat(probe.changeChecks()).as("the effective configuration stays").isEmpty();
  }

  /** A library at https://probe.example.org/a/x on a profile for https://probe.example.org/a. */
  private KnowledgeLibrary connectedLibrary(String settings) {
    KnowledgeLibrary library = ownLibrary("https://probe.example.org/a/x");
    library.updateSourceSettings(settings);
    ConnectionProfile previous = profile("https://probe.example.org/a", null);
    when(connections.findById(library.getId()))
        .thenReturn(Optional.of(new LibraryConnection(library.getId(), previous.getId(), NOW)));
    return library;
  }

  private UUID currentProfileOf(KnowledgeLibrary library) {
    return connections.findById(library.getId()).orElseThrow().getProfileId();
  }

  private KnowledgeLibrary ownLibrary(String url) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage",
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            url,
            null,
            "nutzer:geheim",
            false);
    rows.put(library.getId(), library);
    return library;
  }

  private ConnectionProfile profile(String serverUrl, String defaults) {
    ConnectionProfile profile = new ConnectionProfile(ProfileProbeSourceConnector.TYPE, NOW);
    ReflectionTestUtils.setField(profile, "name", "Zugang " + serverUrl);
    ReflectionTestUtils.setField(profile, "serverUrl", serverUrl);
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.PERSONAL_SECRET);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(profile, "connectorSettings", defaults);
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
    return profile;
  }

  private LibraryConnectionService service(ProfileProbeSourceConnector connector) {
    SourceConnectorRegistry registry =
        new SourceConnectorRegistry(
            List.of(connector, new UploadSourceConnector(mock(UploadedOriginalStore.class))));
    TransitionWiring wiring = new TransitionWiring(registry, connections, profiles, libraries);
    return new LibraryConnectionService(
        connections,
        profiles,
        libraries,
        registry,
        mock(ConnectorLockService.class),
        mock(ProfileRequirements.class),
        mock(ProfileRequirementService.class),
        wiring.secrets,
        wiring.effective,
        wiring.transitions,
        mock(SourceConsentService.class),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** The probe, binding stored credentials to the first path segment like a file share. */
  private static final class ShareBoundProbe extends ProfileProbeSourceConnector {

    @Override
    public String credentialBinding(SourceSettings settings) {
      return java.net.URI.create(settings.sourceUrl()).getPath().split("/")[1];
    }
  }
}
