package io.opaa.directory.sync.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DirectoryConnectorType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.common.ValidationException;
import io.opaa.directory.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.directory.sync.keycloak.KeycloakRealmAddress;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The stored service-account secret is only ever sent to the admin address it was stored for. */
class DirectoryConnectorServiceProbeTest {

  private static final String ISSUER = "https://sso.example.org/realms/haus";
  private static final String STORED_SECRET = "gespeichertes-geheimnis";

  private final DirectoryConnectorRepository repository = mock(DirectoryConnectorRepository.class);
  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
  private final KeycloakDirectoryConnector keycloak = mock(KeycloakDirectoryConnector.class);
  private final DirectoryConnectorService service =
      new DirectoryConnectorService(
          repository,
          providers,
          mock(OidcAddressPolicy.class),
          keycloak,
          mock(AuditEventRecorder.class),
          Clock.systemUTC());

  private OidcProvider provider;

  @BeforeEach
  void setUp() {
    provider =
        new OidcProvider("Keycloak", ISSUER, "opaa", null, OidcClaimMapping.keycloakDefaults());
    when(providers.findById(provider.getId())).thenReturn(Optional.of(provider));
    when(keycloak.probe(any(), anyString(), anyString()))
        .thenReturn(new KeycloakDirectoryConnector.ProbeOutcome(true, "ok"));
  }

  @Test
  void theStoredSecretIsUsedForTheStoredAddress() {
    storeConnector("https://keycloak.intern:8443");

    probe("https://KEYCLOAK.intern:8443/", null);

    verify(keycloak)
        .probe(
            eq(new KeycloakRealmAddress("https://KEYCLOAK.intern:8443", "haus")),
            eq("opaa-directory"),
            eq(STORED_SECRET));
  }

  @Test
  void theStoredSecretIsUsedForTheDerivedAddressNamedExplicitly() {
    storeConnector(null);

    probe("https://sso.example.org", null);

    verify(keycloak).probe(any(), eq("opaa-directory"), eq(STORED_SECRET));
  }

  // regression guard for #3358 (5): a freely chosen admin URL never receives the stored secret
  @Test
  void aChangedAddressWithoutASecretIsRefusedBeforeAnythingIsSent() {
    storeConnector("https://keycloak.intern:8443");

    assertThatThrownBy(() -> probe("https://angreifer.example", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Admin-API-Adresse hat sich geändert");

    verify(keycloak, never()).probe(any(), anyString(), anyString());
  }

  @Test
  void thePathCountsDecodedAndWithoutTrailingSlashes() {
    storeConnector("https://keycloak.intern/auth%2Dbereich");

    probe("https://keycloak.intern/auth-bereich//", null);
    verify(keycloak).probe(any(), eq("opaa-directory"), eq(STORED_SECRET));

    assertThatThrownBy(() -> probe("https://keycloak.intern/anderer-pfad", null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aChangedAddressWithItsOwnSecretIsProbed() {
    storeConnector("https://keycloak.intern:8443");

    probe("https://keycloak-neu.intern", "neues-geheimnis");

    verify(keycloak).probe(any(), eq("opaa-directory"), eq("neues-geheimnis"));
  }

  @Test
  void anOverrideDifferentFromTheDerivedAddressIsAChange() {
    storeConnector(null);

    assertThatThrownBy(() -> probe("https://sso.example.org:8443", null))
        .isInstanceOf(ValidationException.class);
    verify(keycloak, never()).probe(any(), anyString(), anyString());
  }

  private void storeConnector(String baseUrl) {
    DirectoryConnector connector =
        new DirectoryConnector(
            UUID.randomUUID(),
            provider.getId(),
            DirectoryConnectorType.KEYCLOAK,
            baseUrl,
            "opaa-directory",
            STORED_SECRET,
            Clock.systemUTC().instant());
    when(repository.findByProviderId(provider.getId())).thenReturn(Optional.of(connector));
  }

  private void probe(String baseUrl, String secret) {
    KeycloakDirectoryConnector.ProbeOutcome outcome =
        service.probe(
            provider.getId(), DirectoryConnectorType.KEYCLOAK, baseUrl, "opaa-directory", secret);
    assertThat(outcome.success()).isTrue();
  }
}
