package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.registry.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A default that changes the scopes a profile's sign-in asks for changes its registration: the
 * secrets of its libraries were issued for the other scopes and are discarded, as for a new client
 * id (ADR-0038, Entscheidung 7). A default that leaves the scopes keeps them.
 */
class ProfileScopeChangeTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final String SERVER = "https://probe.example.org";
  private static final CurrentUser ADMIN =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Verwaltung");

  /**
   * The probe asking for more scopes in edition {@code DC} while {@code scopesFollowEdition}, the
   * same scopes in every edition otherwise.
   */
  private static final class ScopedProbe extends ProfileProbeSourceConnector {
    private volatile boolean scopesFollowEdition = true;

    @Override
    public String signInScopes(ConnectorData profileDefaults) {
      return scopesFollowEdition
              && profileDefaults != null
              && "DC".equals(profileDefaults.get("edition"))
          ? "probe.read probe.team"
          : "probe.read";
    }
  }

  private final ScopedProbe probe = new ScopedProbe();

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final SourceConnectorRegistry registry =
      TestSourceConnectors.connectors().with(probe).registry();
  private final TransitionWiring wiring =
      new TransitionWiring(registry, connections, profiles, libraries);
  private final List<LibraryConnection> connected = new ArrayList<>();
  private final ConnectionProfileService service =
      new ConnectionProfileService(
          profiles,
          connections,
          libraries,
          registry,
          wiring.secrets,
          TestPersonCounts.NO_PERSONS,
          TestPersonCounts.NO_CONSENTS,
          TestPersonCounts.numbers(),
          wiring.transitions,
          mock(CredentialsEncryptor.class),
          wiring.audit,
          mock(CapabilityService.class),
          new PrivateLibraryRelease(
              connections,
              libraries,
              wiring.transitions,
              mock(io.opaa.notification.NotificationService.class),
              Clock.fixed(NOW, ZoneOffset.UTC)),
          mock(ProfileFullSync.class),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private ConnectionProfile profile;

  @BeforeEach
  void aProfileWithEditionCloud() {
    profile = new ConnectionProfile(ProfileProbeSourceConnector.TYPE, NOW);
    ReflectionTestUtils.setField(profile, "name", "Zugang Probe");
    ReflectionTestUtils.setField(profile, "serverUrl", SERVER);
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.PERSONAL_SECRET);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(profile, "connectorSettings", "{\"edition\":\"CLOUD\"}");
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
    when(connections.findByProfileId(profile.getId())).thenReturn(connected);
    when(connections.findById(any()))
        .thenAnswer(
            call ->
                connected.stream()
                    .filter(row -> row.getLibraryId().equals(call.getArgument(0)))
                    .findFirst());
  }

  @Test
  void aDefaultThatChangesTheScopesDiscardsTheSecretsOfTheLibraries() {
    KnowledgeLibrary library = library("/a");

    service.update(ADMIN, profile.getId(), values("DC"), null, true);

    assertThat(wiring.store.ownSecretOf(library.getId())).isEmpty();
  }

  @Test
  void aDefaultThatLeavesTheScopesKeepsTheSecrets() {
    probe.scopesFollowEdition = false;
    KnowledgeLibrary library = library("/a");

    service.update(ADMIN, profile.getId(), values("DC"), null, true);

    assertThat(wiring.store.ownSecretOf(library.getId()))
        .as("the sign-in asks for the same scopes in either edition")
        .isPresent();
  }

  private ConnectionProfileValues values(String edition) {
    return new ConnectionProfileValues(
        "Zugang Probe",
        SERVER,
        ConnectionAuthMethod.PERSONAL_SECRET,
        ConnectionOwnership.LIBRARY,
        null,
        null,
        null,
        null,
        ConnectorData.of(Map.of("edition", edition)));
  }

  private KnowledgeLibrary library(String path) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage " + path,
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            SERVER + path,
            "nutzer:geheim");
    library.updateSourceSettings("{\"topic\":\"Wetter\"}");
    rows.put(library.getId(), library);
    connected.add(new LibraryConnection(library.getId(), profile.getId(), NOW));
    wiring.store.moveIn(profile.getId(), library, SERVER);
    return library;
  }
}
