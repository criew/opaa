package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceChangeGate.Answers;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A profile change passes the connector of every library whose effective configuration it alters:
 * all are checked before anything is written, one refusal leaves profile and libraries as they
 * were, the connector is asked once per distinct configuration, and an accepted change discards run
 * state and leaves the audit entries of a direct change.
 */
class ProfileChangeTransitionTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final String SERVER = "https://probe.example.org";
  private static final CurrentUser ADMIN =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Verwaltung");

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final ProfileProbeSourceConnector probe = new ProfileProbeSourceConnector();
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
          wiring.transitions,
          mock(CredentialsEncryptor.class),
          wiring.audit,
          mock(CapabilityService.class),
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
  void aChangedDefaultAsksTheConnectorOfEveryLibraryAndDiscardsTheirRunState() {
    KnowledgeLibrary first = library("/a", "Wetter");
    KnowledgeLibrary second = library("/b", "Verkehr");

    service.update(ADMIN, profile.getId(), values("DC", null), null, false);

    List<ProfileProbeSourceConnector.ChangeCheck> checks = probe.changeChecks();
    assertThat(checks).hasSize(2);
    assertThat(checks)
        .allSatisfy(
            check -> {
              assertThat(check.stored().connectorSettings().get("edition")).isEqualTo("CLOUD");
              assertThat(check.requested().connectorSettings().get("edition")).isEqualTo("DC");
              assertThat(check.replacesConnection()).isFalse();
            });
    assertThat(probe.sourceChanges())
        .extracting(ProfileProbeSourceConnector.SourceChange::libraryId)
        .containsExactlyInAnyOrder(first.getId(), second.getId());
    assertThat(sourceUpdates()).containsOnlyKeys(first.getId(), second.getId());
    assertThat(sourceUpdates().get(first.getId())).containsExactly("edition");
    assertThat(profile.getConnectorSettings()).contains("DC");
  }

  @Test
  void aRefusalOfTheThirdLibraryLeavesTheProfileAndEveryLibraryAsTheyWere() {
    KnowledgeLibrary first = library("/a", "Wetter");
    library("/b", "Verkehr");
    KnowledgeLibrary third = library("/c", ProfileProbeSourceConnector.CLOUD_ONLY_TOPIC);

    assertThatThrownBy(
            () -> service.update(ADMIN, profile.getId(), values("DC", null), null, false))
        .isInstanceOfSatisfying(
            ValidationException.class,
            refused -> {
              assertThat(refused.getCode()).isEqualTo(ChangeRejection.PROFILE_CHANGE_REJECTED);
              assertThat(refused.getMessage())
                  .contains("1 Bibliothek")
                  .contains("Einstellungen: 1")
                  .doesNotContain("Edition CLOUD");
            });

    assertThat(profile.getConnectorSettings()).contains("CLOUD");
    verify(profiles, never()).save(any());
    assertThat(probe.sourceChanges()).isEmpty();
    verify(wiring.audit, never()).recordUserAction(any());
    verify(libraries, never()).eraseSourceCredentials(any());
    assertThat(first.getSourceUrl()).isEqualTo(SERVER + "/a");
    assertThat(third.getSourceSettings()).contains(ProfileProbeSourceConnector.CLOUD_ONLY_TOPIC);
  }

  @Test
  void thePreviewNamesEveryRefusalAndWritesNothing() {
    library("/a", "Wetter");
    KnowledgeLibrary refused = library("/b", ProfileProbeSourceConnector.CLOUD_ONLY_TOPIC);

    ConnectionProfileService.ProfileImpact impact =
        service.preview(profile.getId(), values("DC", null));

    assertThat(impact.connections()).isEqualTo(2);
    assertThat(impact.rejections())
        .singleElement()
        .satisfies(
            rejection -> {
              assertThat(rejection.libraryId()).isEqualTo(refused.getId());
              assertThat(rejection.category()).isEqualTo(ChangeRejection.Category.SETTINGS);
              assertThat(rejection.message()).contains("Edition CLOUD");
            });
    verify(profiles, never()).save(any());
    assertThat(probe.sourceChanges()).isEmpty();
  }

  /** The costly check of a connector runs once per distinct configuration, before the write. */
  @Test
  void theConnectorIsAskedOncePerDistinctConfigurationAcrossCheckAndWrite() {
    library("/same", "Wetter");
    library("/same", "Wetter");
    library("/other", "Wetter");

    Answers answers = service.check(profile.getId(), values("DC", null), false);
    assertThat(probe.changeChecks()).hasSize(2);
    service.update(ADMIN, profile.getId(), values("DC", null), null, false, answers);

    assertThat(probe.changeChecks()).as("the write reuses the answers").isEmpty();
    assertThat(probe.sourceChanges()).hasSize(3);
  }

  /** The connector names the category: an unreachable target is CONNECTION, not SETTINGS. */
  @Test
  void aTargetTheConnectorCannotReachIsRefusedAsAConnection() {
    library("/a", "Wetter");

    ConnectionProfileService.ProfileImpact impact =
        service.preview(
            profile.getId(),
            values("CLOUD", ProfileProbeSourceConnector.UNREACHABLE_PROXY + ":3128"));

    assertThat(impact.rejections())
        .singleElement()
        .satisfies(
            rejection ->
                assertThat(rejection.category()).isEqualTo(ChangeRejection.Category.CONNECTION));
  }

  /** The confirmation is asked before any connector, so a 409 costs no check. */
  @Test
  void aChangeStillNeedingTheConfirmationAsksNoConnector() {
    library("/a", "Wetter");
    ConnectionProfileValues moved =
        new ConnectionProfileValues(
            "Zugang Probe",
            "https://neu.example.org",
            ConnectionAuthMethod.PERSONAL_SECRET,
            ConnectionOwnership.LIBRARY,
            null,
            null,
            null,
            null,
            ConnectorData.of(Map.of("edition", "CLOUD")),
            null,
            false);

    assertThatThrownBy(() -> service.check(profile.getId(), moved, false))
        .isInstanceOf(ConflictException.class);

    assertThat(probe.changeChecks()).isEmpty();
  }

  @Test
  void aNewProxyIsAnEffectiveChangeCheckedAsAConnectionAndAudited() {
    KnowledgeLibrary library = library("/a", "Wetter");

    service.update(ADMIN, profile.getId(), values("CLOUD", "proxy.example.org:3128"), null, false);

    ProfileProbeSourceConnector.ChangeCheck check = probe.changeChecks().getFirst();
    assertThat(check.replacesConnection()).isTrue();
    assertThat(check.requested().sourceProxy()).isEqualTo("proxy.example.org:3128");
    assertThat(check.requested().sourceCredentials()).isEqualTo("nutzer:geheim");
    assertThat(probe.sourceChanges())
        .singleElement()
        .satisfies(change -> assertThat(change.addressChanged()).isFalse());
    assertThat(sourceUpdates().get(library.getId())).containsExactly("sourceProxy");
  }

  @Test
  void aRenameAsksNoConnectorAndLeavesNoLibraryEntry() {
    library("/a", "Wetter");

    ConnectionProfileValues renamed =
        new ConnectionProfileValues(
            "Neuer Name",
            SERVER,
            ConnectionAuthMethod.PERSONAL_SECRET,
            ConnectionOwnership.LIBRARY,
            null,
            null,
            null,
            null,
            ConnectorData.of(Map.of("edition", "CLOUD")),
            null,
            false);
    service.update(ADMIN, profile.getId(), renamed, null, false);

    verify(libraries, never()).findById(any());
    assertThat(probe.changeChecks()).isEmpty();
    assertThat(probe.sourceChanges()).isEmpty();
    assertThat(sourceUpdates()).isEmpty();
  }

  @Test
  void aNewServerAddressMovesTheLibrariesDiscardsTheirSecretsAndTheirRunState() {
    KnowledgeLibrary library = library("/a", "Wetter");
    ConnectionProfileValues moved =
        new ConnectionProfileValues(
            "Zugang Probe",
            "https://neu.example.org",
            ConnectionAuthMethod.PERSONAL_SECRET,
            ConnectionOwnership.LIBRARY,
            null,
            null,
            null,
            null,
            ConnectorData.of(Map.of("edition", "CLOUD")),
            null,
            false);

    service.update(ADMIN, profile.getId(), moved, null, true);

    assertThat(library.getSourceUrl()).isEqualTo("https://neu.example.org/a");
    assertThat(library.getSourceCredentials()).isNull();
    ProfileProbeSourceConnector.ChangeCheck check = probe.changeChecks().getFirst();
    assertThat(check.replacesConnection()).as("no secret to check a connection with").isFalse();
    assertThat(probe.sourceChanges())
        .singleElement()
        .satisfies(change -> assertThat(change.addressChanged()).isTrue());
    assertThat(sourceUpdates().get(library.getId()))
        .containsExactlyInAnyOrder("sourceUrl", "sourceCredentials");
  }

  private ConnectionProfileValues values(String edition, String proxy) {
    return new ConnectionProfileValues(
        "Zugang Probe",
        SERVER,
        ConnectionAuthMethod.PERSONAL_SECRET,
        ConnectionOwnership.LIBRARY,
        null,
        null,
        null,
        null,
        ConnectorData.of(Map.of("edition", edition)),
        proxy,
        false);
  }

  /** A library on the profile at {@code path} below its server address, with a stored secret. */
  private KnowledgeLibrary library(String path, String topic) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage " + path,
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            SERVER + path,
            null,
            "nutzer:geheim",
            false);
    library.updateSourceSettings("{\"topic\":\"" + topic + "\"}");
    rows.put(library.getId(), library);
    connected.add(new LibraryConnection(library.getId(), profile.getId(), NOW));
    return library;
  }

  /** The changed fields of every LIBRARY_SOURCE_UPDATED entry, by library. */
  @SuppressWarnings("unchecked")
  private Map<UUID, List<String>> sourceUpdates() {
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(wiring.audit, atLeastOnce()).recordUserAction(events.capture());
    return events.getAllValues().stream()
        .filter(event -> event.eventType() == AuditEventType.LIBRARY_SOURCE_UPDATED)
        .collect(
            Collectors.toMap(
                AuditEvent::objectId, event -> (List<String>) event.after().get("changedFields")));
  }
}
