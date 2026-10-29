package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A profile naming its own scopes passes the connector's scope check before it is stored: scopes
 * its defaults cannot work with are refused at saving, not at every later run.
 */
class ProfileScopeCheckTest {

  private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
  private static final CurrentUser ADMIN =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Verwaltung");

  /** The probe signing libraries in by OAuth, refusing own scopes without {@code probe.team}. */
  private static final class TeamScopeProbe extends ProfileProbeSourceConnector {

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
                      ConnectionOwnership.LIBRARY)));
    }

    @Override
    public void checkProfileScopes(String profileScopes, ConnectorData profileDefaults) {
      if (!List.of(profileScopes.split(" ")).contains("probe.team")) {
        throw new ValidationException("Die Probe braucht den Scope probe.team");
      }
    }
  }

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final SourceConnectorRegistry registry =
      TestSourceConnectors.connectors().with(new TeamScopeProbe()).registry();
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
    ConnectionProfileValues values =
        new ConnectionProfileValues(
            "Zugang Team",
            "https://probe.example.org",
            ConnectionAuthMethod.OAUTH,
            ConnectionOwnership.LIBRARY,
            "opaa",
            null,
            null,
            "probe.read",
            null,
            null);

    assertThatThrownBy(
            () -> service.create(ADMIN, ProfileProbeSourceConnector.TYPE, values, "geheim"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("probe.team");
    verify(profiles, never()).save(any());
  }
}
