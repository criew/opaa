package io.opaa.group.sync.connector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DirectoryConnectorType;
import io.opaa.auth.oidc.OidcAddressPolicy;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.group.sync.DirectoryUnavailableException;
import io.opaa.group.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.TargetAddressValidator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaSystemException;

/**
 * {@link ProviderDirectoryClient}'s one hard contract, against mocked collaborators: <b>every</b>
 * way this method can fail ends as the declared {@link DirectoryUnavailableException}. Anything
 * else escapes {@code DirectorySyncService}'s catch - no {@code UNREACHABLE} outcome, no audit
 * entry, no status line - and leaves the scheduled run due again one minute later, indefinitely.
 */
class ProviderDirectoryClientTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final UUID PROVIDER_ID = UUID.randomUUID();

  private DirectoryConnectorRepository connectors;
  private OidcProviderRepository providers;
  private KeycloakDirectoryConnector keycloak;
  private ProviderDirectoryClient client;

  @BeforeEach
  void setUp() {
    connectors = mock(DirectoryConnectorRepository.class);
    providers = mock(OidcProviderRepository.class);
    keycloak = mock(KeycloakDirectoryConnector.class);
    client =
        new ProviderDirectoryClient(
            connectors,
            providers,
            new OidcAddressPolicy(new TargetAddressValidator(true, List.of("kc.example"))),
            keycloak);
  }

  /**
   * The failure a persistence problem really arrives as: Spring's repository proxy translates every
   * cause into a {@link org.springframework.dao.DataAccessException}, so catching the cause type
   * would catch nothing.
   */
  @Test
  void aFailingDatabaseReadIsReportedAsUnreachable() {
    when(connectors.findByProviderId(PROVIDER_ID))
        .thenThrow(
            new JpaSystemException(
                new RuntimeException(
                    new CredentialsEncryptionKeyMissingException("Schlüssel fehlt"))));

    assertThatThrownBy(() -> client.fetchSnapshot(ORGANIZATION_ID, PROVIDER_ID))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Schlüssel fehlt");
  }

  @Test
  void aFailingProviderReadIsReportedAsUnreachable() {
    when(connectors.findByProviderId(PROVIDER_ID)).thenReturn(Optional.of(connector("geheim")));
    when(providers.findById(PROVIDER_ID))
        .thenThrow(new JpaSystemException(new RuntimeException("Verbindung weg")));

    assertThatThrownBy(() -> client.fetchSnapshot(ORGANIZATION_ID, PROVIDER_ID))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Verbindung weg");
  }

  @Test
  void anAbsentAccessIsReportedAsUnreachable() {
    when(connectors.findByProviderId(PROVIDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> client.fetchSnapshot(ORGANIZATION_ID, PROVIDER_ID))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("kein Verzeichniszugang");
  }

  /** {@code null} is what the converter yields for a value it cannot decrypt. */
  @Test
  void anUndecryptableSecretIsReportedAsUnreachableAndNeverSentAsASignIn() throws Exception {
    when(connectors.findByProviderId(PROVIDER_ID)).thenReturn(Optional.of(connector(null)));
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));

    assertThatThrownBy(() -> client.fetchSnapshot(ORGANIZATION_ID, PROVIDER_ID))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("nicht entschlüsseln");
    verify(keycloak, never()).fetchSnapshot(any(), any(), any());
  }

  @Test
  void anIssuerThatIsNoRealmAddressIsReportedAsUnreachable() {
    when(connectors.findByProviderId(PROVIDER_ID)).thenReturn(Optional.of(connector("geheim")));
    when(providers.findById(PROVIDER_ID))
        .thenReturn(Optional.of(provider("https://entra.example/tenant/v2.0")));

    assertThatThrownBy(() -> client.fetchSnapshot(ORGANIZATION_ID, PROVIDER_ID))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Keycloak-Realm-Adresse");
  }

  /** The policy is applied at use time: a narrowed allowlist must stop the call, not the save. */
  @Test
  void anAddressTheAllowlistNoLongerCoversIsReportedAsUnreachable() throws Exception {
    when(connectors.findByProviderId(PROVIDER_ID))
        .thenReturn(Optional.of(connector("geheim", "http://192.168.7.7:8080")));
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));

    assertThatThrownBy(() -> client.fetchSnapshot(ORGANIZATION_ID, PROVIDER_ID))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Admin-API-Adresse");
    verify(keycloak, never()).fetchSnapshot(any(), any(), any());
  }

  @Test
  void anAllowedAccessReachesTheConnectorWithTheDerivedRealm() throws Exception {
    when(connectors.findByProviderId(PROVIDER_ID)).thenReturn(Optional.of(connector("geheim")));
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));

    client.fetchSnapshot(ORGANIZATION_ID, PROVIDER_ID);

    verify(keycloak)
        .fetchSnapshot(
            org.mockito.ArgumentMatchers.argThat(
                address ->
                    address.realm().equals("haus")
                        && address.baseUrl().equals("https://kc.example")),
            org.mockito.ArgumentMatchers.eq("opaa-directory"),
            org.mockito.ArgumentMatchers.eq("geheim"));
  }

  private static DirectoryConnector connector(String secret) {
    return connector(secret, null);
  }

  private static DirectoryConnector connector(String secret, String baseUrl) {
    DirectoryConnector connector =
        new DirectoryConnector(
            ORGANIZATION_ID,
            PROVIDER_ID,
            DirectoryConnectorType.KEYCLOAK,
            baseUrl,
            "opaa-directory",
            "platzhalter",
            Instant.now());
    // The entity refuses a null secret on the way in; only the converter produces one on the way
    // out, which is exactly the state under test here.
    setClientSecret(connector, secret);
    return connector;
  }

  private static void setClientSecret(DirectoryConnector connector, String secret) {
    try {
      var field = DirectoryConnector.class.getDeclaredField("clientSecret");
      field.setAccessible(true);
      field.set(connector, secret);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static OidcProvider provider() {
    return provider("https://kc.example/realms/haus");
  }

  private static OidcProvider provider(String issuerUri) {
    return new OidcProvider(
        "Haus", issuerUri, "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());
  }
}
