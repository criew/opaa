package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.registry.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.DefaultKey;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.Revocation;
import io.opaa.indexing.spi.SignIn;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.security.CredentialsEncryptor;
import java.net.URI;
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
 * A profile naming its own scopes passes the connector's scope check before it is stored: scopes
 * its defaults cannot work with are refused at saving, not at every later run. A profile naming
 * none is not checked.
 */
class ProfileScopeCheckTest {

  private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
  private static final String SERVER = "https://probe.example.org";
  private static final CurrentUser ADMIN =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Verwaltung");

  /**
   * The probe signing libraries in by OAuth; under its default {@code teamApp} it refuses own
   * scopes without {@code probe.team}.
   */
  static class TeamScopeProbe extends ProfileProbeSourceConnector {

    @Override
    public SourceConnectorDescriptor descriptor() {
      return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Team-Scopes")
          .withProfiles(
              ProfileDeclaration.of(
                      ConnectionProfileSupport.REQUIRED,
                      SignIn.oauth(
                          new OAuthAuth(
                              new Endpoint.Fixed(URI.create("https://login.example.org/authorize")),
                              new Endpoint.Fixed(URI.create("https://login.example.org/token")),
                              new Revocation.None(),
                              "probe.read",
                              Map.of(),
                              ClientAuthentication.CLIENT_SECRET_POST),
                          ConnectionOwnership.LIBRARY))
                  .withDefaults(DefaultKey.bool("teamApp", "Team-App").onlyOnProfile()));
    }

    @Override
    public Set<String> settingsKeys() {
      return Set.of("edition", "topic", "teamApp");
    }

    @Override
    public void checkProfileScopes(String profileScopes, ConnectorData profileDefaults) {
      if (profileDefaults != null
          && Boolean.TRUE.equals(profileDefaults.get("teamApp"))
          && !List.of(profileScopes.split(" ")).contains("probe.team")) {
        throw new ValidationException("Die Team-App braucht den Scope probe.team");
      }
    }
  }

  private final TeamScopeProbe probe = spy(new TeamScopeProbe());
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final SourceConnectorRegistry registry =
      TestSourceConnectors.connectors().with(probe).registry();
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
          TestPersonCounts.NO_CONSENTS,
          TestPersonCounts.numbers(),
          wiring.transitions,
          mock(CredentialsEncryptor.class),
          wiring.audit,
          mock(CapabilityService.class),
          mock(PrivateLibraryRelease.class),
          mock(ProfileFullSync.class),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void ownScopesTheConnectorRefusesAreNotStored() {
    assertThatThrownBy(
            () ->
                service.create(
                    ADMIN, ProfileProbeSourceConnector.TYPE, values("probe.read", true), "geheim"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("probe.team");
    verify(profiles, never()).save(any());
  }

  @Test
  void switchingTheDefaultUnderUnchangedOwnScopesIsRefusedAndNotStored() {
    ConnectionProfile profile = new ConnectionProfile(ProfileProbeSourceConnector.TYPE, NOW);
    ReflectionTestUtils.setField(profile, "name", "Zugang Team");
    ReflectionTestUtils.setField(profile, "serverUrl", SERVER);
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.OAUTH);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(profile, "clientId", "opaa");
    ReflectionTestUtils.setField(profile, "scopes", "probe.read");
    ReflectionTestUtils.setField(profile, "connectorSettings", "{\"teamApp\":false}");
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));

    assertThatThrownBy(
            () -> service.update(ADMIN, profile.getId(), values("probe.read", true), null, true))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("probe.team");
    verify(profiles, never()).save(any());
  }

  @Test
  void aProfileWithoutOwnScopesIsNotChecked() {
    catchThrowable(
        () ->
            service.create(ADMIN, ProfileProbeSourceConnector.TYPE, values(null, true), "geheim"));

    verify(probe).readProfileDefaults(any());
    verify(probe, never()).checkProfileScopes(anyString(), any());
  }

  private static ConnectionProfileValues values(String scopes, boolean teamApp) {
    return new ConnectionProfileValues(
        "Zugang Team",
        SERVER,
        ConnectionAuthMethod.OAUTH,
        ConnectionOwnership.LIBRARY,
        "opaa",
        null,
        null,
        scopes,
        ConnectorData.of(Map.of("teamApp", teamApp)),
        null);
  }
}
