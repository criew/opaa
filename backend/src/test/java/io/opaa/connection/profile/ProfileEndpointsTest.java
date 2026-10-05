package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.security.CredentialsEncryptor;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The endpoints a profile names where its connector's OAuth sign-in leaves them to it: required and
 * an absolute http(s) address exactly there, refused everywhere else, and a changed one is a
 * changed registration that discards the persons' grants.
 */
class ProfileEndpointsTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final String SERVER = "https://keycloak.example.org";
  private static final ProfileEndpoints REALM =
      new ProfileEndpoints(
          SERVER + "/realms/haus/protocol/openid-connect/auth",
          SERVER + "/realms/haus/protocol/openid-connect/token",
          SERVER + "/realms/haus/protocol/openid-connect/revoke");

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final SourceConnectorRegistry registry =
      TestSourceConnectors.connectors().with(new RealmProbe()).registry();
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
  void endpointsLeftToTheProfileAreRequiredAndAbsoluteAddresses() {
    ProfileDeclaration declaration = new RealmProbe().descriptor().profileDeclaration();

    assertThat(service.validate(declaration, "Probe", values(REALM, "Realm"), null).endpoints())
        .isEqualTo(REALM);
    assertThatThrownBy(
            () -> service.validate(declaration, "Probe", values(ProfileEndpoints.NONE, "R"), null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("authorizationEndpoint ist für diese Anmeldeart erforderlich");
    for (String bad :
        new String[] {
          "ftp://keycloak.example.org/token",
          "https://admin:geheim@keycloak.example.org/token",
          "https://keycloak.example.org/token#x",
          "/realms/haus/token"
        }) {
      assertThatThrownBy(
              () ->
                  service.validate(
                      declaration,
                      "Probe",
                      values(
                          new ProfileEndpoints(REALM.authorization(), bad, REALM.revocation()),
                          "R"),
                      null))
          .as(bad)
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("tokenEndpoint");
    }
  }

  @Test
  void anEndpointTheConnectorFixesIsNoProfilesToName() {
    ProfileDeclaration fixed =
        ProfileDeclaration.of(
            ConnectionProfileSupport.REQUIRED,
            SignIn.oauth(
                new OAuthAuth(
                    new Endpoint.Fixed(URI.create("https://login.example.org/authorize")),
                    new Endpoint.Fixed(URI.create("https://login.example.org/token")),
                    new Revocation.None(),
                    null,
                    Map.of(),
                    ClientAuthentication.CLIENT_SECRET_POST),
                ConnectionOwnership.PERSON));

    assertThatThrownBy(() -> service.validate(fixed, "Probe", values(REALM, "R"), null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("authorizationEndpoint gehört nicht zu dieser Anmeldeart");
    assertThatCode(() -> service.validate(fixed, "Probe", values(ProfileEndpoints.NONE, "R"), null))
        .doesNotThrowAnyException();
  }

  @Test
  void aChangedEndpointIsAChangedRegistration() {
    ConnectionProfile profile = new ConnectionProfile(RealmProbe.TYPE, NOW);
    profile.replace(values(REALM, "Realm"), null, NOW);
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");
    ProfileEndpoints moved =
        new ProfileEndpoints(
            REALM.authorization(), SERVER + "/realms/andere/token", REALM.revocation());

    assertThatCode(
            () -> service.update(admin, profile.getId(), values(REALM, "Realm neu"), null, false))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> service.update(admin, profile.getId(), values(moved, "Realm neu"), null, false))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("verbundener Konten");
    service.update(admin, profile.getId(), values(moved, "Realm neu"), null, true);

    assertThat(profile.getEndpoints()).isEqualTo(moved);
    assertThat(ReflectionTestUtils.getField(profile, "tokenEndpoint")).isEqualTo(moved.token());
  }

  private static ConnectionProfileValues values(ProfileEndpoints endpoints, String name) {
    return new ConnectionProfileValues(
        name,
        SERVER,
        ConnectionAuthMethod.OAUTH,
        ConnectionOwnership.PERSON,
        "opaa",
        null,
        null,
        null,
        null,
        null,
        false,
        endpoints);
  }

  /** The probe signing persons in by OAuth at the endpoints of a realm its profile names. */
  private static final class RealmProbe extends ProfileProbeSourceConnector {

    @Override
    public SourceConnectorDescriptor descriptor() {
      return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Realm")
          .withProfiles(
              ProfileDeclaration.of(
                      ConnectionProfileSupport.REQUIRED,
                      SignIn.oauth(
                          new OAuthAuth(
                              new Endpoint.FromProfile(),
                              new Endpoint.FromProfile(),
                              new Revocation.Rfc7009(new Endpoint.FromProfile()),
                              "openid offline_access",
                              Map.of(),
                              ClientAuthentication.CLIENT_SECRET_BASIC),
                          ConnectionOwnership.PERSON))
                  .withAddress(ServerAddressRule.schemes("https")));
    }
  }
}
