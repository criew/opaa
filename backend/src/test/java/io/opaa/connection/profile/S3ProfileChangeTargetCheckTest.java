package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceChangeGate.Answers;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.test.SourceTypes;
import java.net.URI;
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
 * A changed default of an S3 profile passes the real S3 connector of every library on it: its
 * target check runs before the write and once per distinct effective configuration, the write asks
 * nothing again, and every library's run state is discarded.
 */
class S3ProfileChangeTargetCheckTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final String SERVER = "https://s3.example.org";
  private static final CurrentUser ADMIN =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Verwaltung");

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final S3ClientFactory clientFactory = mock(S3ClientFactory.class);
  private final SourceSyncStateRepository syncStates = mock(SourceSyncStateRepository.class);
  private final SourceConnectorRegistry registry =
      TestSourceConnectors.connectors()
          .s3ClientFactory(clientFactory)
          .sourceSyncStateRepository(syncStates)
          .registry();
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
  void anS3Profile() {
    profile = new ConnectionProfile(SourceTypes.S3, NOW);
    ReflectionTestUtils.setField(profile, "name", "Zugang Speicher");
    ReflectionTestUtils.setField(profile, "serverUrl", SERVER);
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.PERSONAL_SECRET);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(
        profile, "connectorSettings", "{\"region\":\"us-east-1\",\"pathStyle\":true}");
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
  void theTargetCheckRunsOncePerDistinctConfigurationBeforeTheWrite() throws Exception {
    KnowledgeLibrary first = library("akten");
    KnowledgeLibrary second = library("akten");
    KnowledgeLibrary third = library("protokolle");

    Answers answers = service.check(profile.getId(), region("eu-west-1"), null, false);

    verify(clientFactory, times(2))
        .validateTargets(eq(URI.create(SERVER)), isNull(), eq(true), any());

    service.update(ADMIN, profile.getId(), region("eu-west-1"), null, false, answers);

    verify(clientFactory, times(2)).validateTargets(any(URI.class), any(), anyBoolean(), any());
    for (KnowledgeLibrary library : List.of(first, second, third)) {
      verify(syncStates).deleteByLibraryId(library.getId());
    }
    assertThat(profile.getConnectorSettings()).contains("eu-west-1");
  }

  /**
   * A library without a secret - as after a new server address - still passes the target check: it
   * needs no credentials, so the change is not refused as a setting.
   */
  @Test
  void aLibraryWithoutASecretPassesTheTargetCheck() throws Exception {
    KnowledgeLibrary library = library("akten");
    library.dropSourceCredentials();

    assertThat(service.preview(profile.getId(), region("eu-west-1"), null).rejections()).isEmpty();
    service.update(ADMIN, profile.getId(), region("eu-west-1"), null, false);

    verify(clientFactory, atLeastOnce())
        .validateTargets(eq(URI.create(SERVER)), isNull(), eq(true), any());
    assertThat(profile.getConnectorSettings()).contains("eu-west-1");
  }

  private ConnectionProfileValues region(String region) {
    return new ConnectionProfileValues(
        "Zugang Speicher",
        SERVER,
        ConnectionAuthMethod.PERSONAL_SECRET,
        ConnectionOwnership.LIBRARY,
        null,
        null,
        null,
        null,
        ConnectorData.of(Map.of("region", region, "pathStyle", true)),
        null,
        false);
  }

  /** A library on the profile reading {@code bucket}, with a stored key of its own. */
  private KnowledgeLibrary library(String bucket) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage " + bucket,
            null,
            UUID.randomUUID(),
            SourceTypes.S3,
            null,
            SERVER,
            null,
            "zugriff:geheim",
            false);
    library.updateSourceSettings("{\"scopes\":[{\"bucket\":\"" + bucket + "\"}]}");
    rows.put(library.getId(), library);
    connected.add(new LibraryConnection(library.getId(), profile.getId(), NOW));
    return library;
  }
}
