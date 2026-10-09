package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.connection.oauth.ProfileSignIn;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ClientCredentialsProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.permission.CapabilityService;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.LoopbackTls;
import io.opaa.test.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A profile naming its own token endpoint ({@code Endpoint.FromProfile}) against two fake
 * authorization servers over {@code https://}: its stored client secret never reaches a token
 * endpoint the profile is changed to; only the secret entered with the change does.
 */
class EndpointChangeClientSecretTest {

  private static final String CLIENT_ID = "opaa-client";
  private static final String OLD_SECRET = "altes-geheimnis";
  private static final String NEW_SECRET = "neues-geheimnis";
  private static final CurrentUser ADMIN =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");

  private final FakeAuthorizationServer registered = FakeAuthorizationServer.overTls();
  private final FakeAuthorizationServer elsewhere = FakeAuthorizationServer.overTls();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T08:00:00Z"));
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final CredentialsEncryptor encryptor = mock(CredentialsEncryptor.class);
  private ConnectionProfile profile;

  @AfterEach
  void stop() {
    registered.close();
    elsewhere.close();
  }

  @AfterAll
  static void forgetTheLoopbackCertificate() {
    LoopbackTls.restore();
  }

  /** Regression guard for #2297: the secret goes only where it was entered for. */
  @Test
  void theNextSignInSendsOnlyTheNewSecretToTheNewTokenEndpoint() {
    SourceConnectorRegistry registry =
        TestSourceConnectors.connectors()
            .with(
                new ClientCredentialsProbeSourceConnector(
                    new Endpoint.FromProfile(), ClientAuthentication.CLIENT_SECRET_BASIC))
            .registry();
    when(encryptor.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));
    when(encryptor.decrypt(anyString()))
        .thenAnswer(call -> call.<String>getArgument(0).substring("enc:".length()));
    when(profiles.findById(any())).thenAnswer(call -> Optional.ofNullable(profile));
    ConnectionProfileService service = service(registry);
    ProfileSignIn signIn = signIn(registry);
    profile =
        service.create(
            ADMIN,
            ClientCredentialsProbeSourceConnector.TYPE,
            values(registered.tokenEndpoint().toString()),
            OLD_SECRET);
    String moved = elsewhere.tokenEndpoint().toString();

    assertThatThrownBy(() -> service.update(ADMIN, profile.getId(), values(moved), null, true))
        .isInstanceOf(ValidationException.class)
        .hasFieldOrPropertyWithValue("code", "CONNECTION_PROFILE_CLIENT_SECRET_REQUIRED");
    assertThat(profile.getEndpoints().token()).isEqualTo(registered.tokenEndpoint().toString());

    service.update(ADMIN, profile.getId(), values(moved), NEW_SECRET, true);
    signIn.mint(profile.getId());

    assertThat(registered.requests()).isEmpty();
    assertThat(elsewhere.requests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.authorization()).isEqualTo(basic(NEW_SECRET));
              assertThat(request.form()).doesNotContainKey("client_secret");
            });
  }

  private ConnectionProfileService service(SourceConnectorRegistry registry) {
    LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
    var libraries = LibraryRows.over(new HashMap<UUID, KnowledgeLibrary>());
    TransitionWiring wiring = new TransitionWiring(registry, connections, profiles, libraries);
    return new ConnectionProfileService(
        profiles,
        connections,
        libraries,
        registry,
        wiring.secrets,
        TestPersonCounts.NO_PERSONS,
        TestPersonCounts.NO_CONSENTS,
        TestPersonCounts.numbers(),
        wiring.transitions,
        encryptor,
        wiring.audit,
        mock(CapabilityService.class),
        mock(PrivateLibraryRelease.class),
        mock(ProfileFullSync.class),
        clock);
  }

  private ProfileSignIn signIn(SourceConnectorRegistry registry) {
    @SuppressWarnings("unchecked")
    ObjectProvider<SourceConnectorRegistry> connectors = mock(ObjectProvider.class);
    when(connectors.getObject()).thenReturn(registry);
    ProfileRegistrations registrations =
        new ProfileRegistrations(
            profiles, encryptor, mock(PlatformTransactionManager.class), connectors, clock);
    return new ProfileSignIn(
        registrations,
        new ServiceAccountTokens(TargetAddressValidator.disabled(), clock),
        TargetAddressValidator.disabled(),
        clock);
  }

  private static ConnectionProfileValues values(String tokenEndpoint) {
    return new ConnectionProfileValues(
        "Zugang mit eigenem Token-Endpunkt",
        "https://quelle.example.org",
        ConnectionAuthMethod.CLIENT_CREDENTIALS,
        ConnectionOwnership.LIBRARY,
        CLIENT_ID,
        null,
        null,
        null,
        null,
        null,
        false,
        new ProfileEndpoints(null, tokenEndpoint, null));
  }

  private static String basic(String secret) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((CLIENT_ID + ":" + secret).getBytes(StandardCharsets.UTF_8));
  }
}
