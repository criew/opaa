package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression guard: a profile change that binds a library's secret anew discards it by the column,
 * not by whether its ciphertext can be read now - an unreadable secret would otherwise reach the
 * new binding once the key is back, and the confirmation would not count it.
 */
class ProfileChangeUnreadableSecretTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final String SERVER = "https://probe.example.org";

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final SourceConnectorRegistry registry =
      TestSourceConnectors.connectors().with(new EditionBoundProbe()).registry();
  private final TransitionWiring wiring =
      new TransitionWiring(registry, connections, profiles, libraries);
  private final ConnectionProfileService service =
      new ConnectionProfileService(
          profiles,
          connections,
          libraries,
          registry,
          wiring.secrets,
          TestPersonCounts.NO_PERSONS,
          TestPersonCounts.numbers(),
          wiring.transitions,
          mock(CredentialsEncryptor.class),
          wiring.audit,
          mock(CapabilityService.class),
          mock(ProfileChangeNotices.class),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aNewBindingDiscardsAnUnreadableSecretByItsColumnAndCountsItForTheConfirmation() {
    ConnectionProfile profile = new ConnectionProfile(ProfileProbeSourceConnector.TYPE, NOW);
    ReflectionTestUtils.setField(profile, "name", "Zugang Probe");
    ReflectionTestUtils.setField(profile, "serverUrl", SERVER);
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.PERSONAL_SECRET);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(profile, "connectorSettings", "{\"edition\":\"CLOUD\"}");
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
    // the key is missing: the attribute reads null, the column holds the ciphertext
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage",
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            SERVER + "/a",
            null,
            null,
            false);
    rows.put(library.getId(), library);
    doReturn(Set.of(library.getId())).when(libraries).findIdsHoldingSourceCredentials(any());
    LibraryConnection connection = new LibraryConnection(library.getId(), profile.getId(), NOW);
    when(connections.findByProfileId(profile.getId())).thenReturn(List.of(connection));
    when(connections.findById(library.getId())).thenReturn(Optional.of(connection));
    ConnectionProfileValues rebound =
        new ConnectionProfileValues(
            "Zugang Probe",
            SERVER,
            ConnectionAuthMethod.PERSONAL_SECRET,
            ConnectionOwnership.LIBRARY,
            null,
            null,
            null,
            null,
            ConnectorData.of(Map.of("edition", "DC")),
            null,
            false);
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");

    assertThatThrownBy(() -> service.update(admin, profile.getId(), rebound, null, false))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("1 Verbindung");
    service.update(admin, profile.getId(), rebound, null, true);

    verify(libraries).eraseSourceCredentials(library.getId());
  }

  /** The probe, binding stored credentials to its edition like a service account to its subject. */
  private static final class EditionBoundProbe extends ProfileProbeSourceConnector {

    @Override
    public String credentialBinding(SourceSettings settings) {
      ConnectorData data = settings.connectorSettings();
      return data == null ? null : (String) data.get("edition");
    }
  }
}
