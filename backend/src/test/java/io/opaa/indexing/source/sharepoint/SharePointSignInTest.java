package io.opaa.indexing.source.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.connection.oauth.ProfileSignIn;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.MutableClock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The profile's own sign-in as the SharePoint connector declares it, against a fake authorization
 * server: client id and secret in the form, the tenant in the token address, the scope of every
 * application permission ({@code .default}) unless the profile names others.
 */
class SharePointSignInTest {

  private static final String TENANT = "contoso.onmicrosoft.com";

  private final FakeAuthorizationServer authorization = new FakeAuthorizationServer();
  private final FakeGraphServer graph = new FakeGraphServer();
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final CredentialsEncryptor encryptor = mock(CredentialsEncryptor.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T12:00:00Z"));

  @AfterEach
  void stop() {
    authorization.close();
    graph.close();
  }

  @Test
  void theClientCredentialsGoToTheTenantsTokenEndpointInTheFormWithTheDefaultScope() {
    ConnectionProfile profile = profile();
    ProfileSignIn signIn = wire(profile);

    Secret token = signIn.mint(profile.getId());

    assertThat(token.value()).isEqualTo(authorization.lastToken());
    assertThat(authorization.requests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.target().getPath()).isEqualTo("/token/" + TENANT + "/v2.0");
              assertThat(request.authorization()).isNull();
              assertThat(request.form())
                  .containsEntry("grant_type", "client_credentials")
                  .containsEntry("client_id", "app-registration")
                  .containsEntry("client_secret", "geheim")
                  .containsEntry("scope", SharePointSourceConnector.SCOPE);
            });
  }

  private ProfileSignIn wire(ConnectionProfile profile) {
    SharePointSourceConnector connector =
        new SharePointSourceConnector(
            graph.origin(),
            authorization.tokenEndpoint() + "/{tenant}/v2.0",
            SharePointTestStores.connections(graph),
            mock(SourceSyncStateRepository.class));
    SourceConnectorRegistry registry = TestSourceConnectors.connectors().with(connector).registry();
    @SuppressWarnings("unchecked")
    ObjectProvider<SourceConnectorRegistry> connectors = mock(ObjectProvider.class);
    when(connectors.getObject()).thenReturn(registry);
    when(profiles.findById(any())).thenReturn(Optional.of(profile));
    ProfileRegistrations registrations =
        new ProfileRegistrations(
            profiles, encryptor, mock(PlatformTransactionManager.class), connectors, clock);
    return new ProfileSignIn(
        registrations,
        new ServiceAccountTokens(TargetAddressValidator.disabled(), clock),
        TargetAddressValidator.disabled(),
        clock);
  }

  private ConnectionProfile profile() {
    ConnectionProfile created =
        new ConnectionProfile(SharePointSourceConnector.TYPE, clock.instant());
    ReflectionTestUtils.setField(created, "name", "Zugang " + UUID.randomUUID());
    ReflectionTestUtils.setField(created, "serverUrl", graph.origin().toString());
    ReflectionTestUtils.setField(created, "authMethod", ConnectionAuthMethod.CLIENT_CREDENTIALS);
    ReflectionTestUtils.setField(created, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(created, "clientId", "app-registration");
    ReflectionTestUtils.setField(created, "tenant", TENANT);
    String ciphertext = "enc:v1:" + UUID.randomUUID();
    ReflectionTestUtils.setField(created, "clientSecretCiphertext", ciphertext);
    when(encryptor.decrypt(ciphertext)).thenReturn("geheim");
    return created;
  }
}
