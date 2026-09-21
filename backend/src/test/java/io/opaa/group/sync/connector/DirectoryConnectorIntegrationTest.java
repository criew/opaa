package io.opaa.group.sync.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DirectoryConnectorType;
import io.opaa.api.types.ProviderType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import io.opaa.group.sync.keycloak.FakeKeycloakServer;
import io.opaa.group.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The stored directory access of a provider (#1817): what a write does to the database, what a read
 * gives back, and what {@link ProviderDirectoryClient} makes of it.
 *
 * <p>{@code FakeDirectoryClient} is the {@code @Primary} {@link DirectoryClient} of this context,
 * so the productive one is injected by its concrete type - the one place in the suite where the
 * real dispatch is exercised. The directory behind it is {@link FakeKeycloakServer}; the same
 * dispatch against a real Keycloak is {@code io.opaa.integration.keycloak}'s.
 */
@OpaaIntegrationTest
class DirectoryConnectorIntegrationTest {

  private static final UUID ORGANIZATION_ID = Organization.DEFAULT_ID;

  @Autowired private DirectoryConnectorService connectorService;
  @Autowired private DirectoryConnectorRepository connectorRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ProviderDirectoryClient providerDirectoryClient;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<UUID> createdProviderIds = new ArrayList<>();

  private FakeKeycloakServer keycloak;

  /** The acting system administrator every audit entry of this class is pseudonymised for. */
  private UUID actorId;

  @BeforeEach
  void setUp() throws IOException {
    cleanUp();
    User actor =
        new User(
            "connector-admin-" + UUID.randomUUID(),
            "https://idp.example/realms/verwaltung",
            "connector-admin@example.com",
            "Verwaltung");
    actor.setOrganizationId(ORGANIZATION_ID);
    actorId = userRepository.save(actor).getId();
    keycloak = new FakeKeycloakServer();
  }

  @AfterEach
  void tearDown() {
    keycloak.close();
    cleanUp();
  }

  private void cleanUp() {
    if (actorId != null) {
      // The pseudonym row goes with the user (ON DELETE CASCADE).
      userRepository.deleteById(actorId);
      actorId = null;
    }
    createdProviderIds.forEach(
        providerId ->
            connectorRepository
                .findByProviderId(providerId)
                .ifPresent(connectorRepository::delete));
    createdProviderIds.forEach(providerRepository::deleteById);
    createdProviderIds.clear();
  }

  // ---------------------------------------------------------------------------------------
  // Storing
  // ---------------------------------------------------------------------------------------

  /** The realm comes from the issuer and the base address from it too while none is overridden. */
  @Test
  void theStoredAccessDerivesRealmAndAddressFromTheIssuer() {
    OidcProvider provider =
        createProvider("https://idp.example/realms/mitarbeitende-" + UUID.randomUUID());

    DirectoryConnectorView view = save(provider, null, "opaa-directory", "geheim");

    assertThat(view.type()).isEqualTo(DirectoryConnectorType.KEYCLOAK);
    assertThat(view.realm()).startsWith("mitarbeitende-");
    assertThat(view.baseUrl()).isEqualTo("https://idp.example");
    assertThat(view.clientId()).isEqualTo("opaa-directory");
  }

  @Test
  void anOverriddenAddressIsWhatTheViewReports() {
    OidcProvider provider =
        createProvider("https://idp.example/realms/mitarbeitende-" + UUID.randomUUID());

    DirectoryConnectorView view = save(provider, "http://127.0.0.1:9999", "opaa-directory", "g");

    assertThat(view.baseUrl()).isEqualTo("http://127.0.0.1:9999");
    assertThat(view.realm()).startsWith("mitarbeitende-");
  }

  /** ADR-0036, Entscheidung 3: the secret is at rest only as ciphertext, never as typed. */
  @Test
  void theSecretIsStoredEncrypted() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());

    save(provider, null, "opaa-directory", "streng-geheim");

    String stored =
        jdbcTemplate.queryForObject(
            "SELECT client_secret FROM directory_connectors WHERE provider_id = ?",
            String.class,
            provider.getId());
    assertThat(stored).startsWith("enc:v1:").doesNotContain("streng-geheim");
  }

  @Test
  void theStoredSecretComesBackDecryptedForTheConnectorOnly() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());
    save(provider, null, "opaa-directory", "streng-geheim");

    assertThat(
            connectorRepository.findByProviderId(provider.getId()).orElseThrow().getClientSecret())
        .isEqualTo("streng-geheim");
  }

  /**
   * Rotating the service account's password is an ordinary write; a fresh IV makes it a new blob.
   */
  @Test
  void aRotatedSecretReplacesTheStoredOne() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());
    save(provider, null, "opaa-directory", "alt");
    String first = storedSecret(provider);

    save(provider, null, "opaa-directory", "neu");

    assertThat(storedSecret(provider)).isNotEqualTo(first);
    assertThat(
            connectorRepository.findByProviderId(provider.getId()).orElseThrow().getClientSecret())
        .isEqualTo("neu");
    assertThat(connectorRepository.findByOrganizationId(ORGANIZATION_ID))
        .filteredOn(connector -> connector.getProviderId().equals(provider.getId()))
        .hasSize(1);
  }

  @Test
  void removingTheAccessRemovesTheStoredSecret() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());
    save(provider, null, "opaa-directory", "geheim");

    connectorService.delete(ORGANIZATION_ID, actorId, provider.getId());

    assertThat(connectorRepository.findByProviderId(provider.getId())).isEmpty();
  }

  @Test
  void removingAnAbsentAccessIsNotAnError() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());

    connectorService.delete(ORGANIZATION_ID, actorId, provider.getId());

    assertThat(connectorRepository.findByProviderId(provider.getId())).isEmpty();
  }

  // ---------------------------------------------------------------------------------------
  // What is refused
  // ---------------------------------------------------------------------------------------

  /** ADR-0025: without a realm in the issuer there is nothing to bind the read to. */
  @Test
  void aProviderWhoseIssuerIsNoRealmAddressGetsNoKeycloakAccess() {
    OidcProvider provider = createProvider("https://entra.example/tenant/v2.0");

    assertThatThrownBy(() -> save(provider, null, "opaa-directory", "geheim"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Keycloak-Realm-Adresse");
  }

  /** The admin API address is subject to the same SSRF policy as the issuer (ADR-0025/3). */
  @Test
  void anAddressOutsideTheAllowlistIsRefused() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());

    assertThatThrownBy(() -> save(provider, "http://192.168.7.7:8080", "opaa-directory", "geheim"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Admin-API-Adresse");
  }

  @Test
  void theLocalAccountRowHasNoDirectory() {
    OidcProvider local = localProviderRow();

    assertThatThrownBy(
            () ->
                connectorService.save(
                    ORGANIZATION_ID,
                    actorId,
                    local.getId(),
                    DirectoryConnectorType.KEYCLOAK,
                    null,
                    "opaa-directory",
                    "geheim"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("lokalen Konten");
  }

  // ---------------------------------------------------------------------------------------
  // Probing and reading
  // ---------------------------------------------------------------------------------------

  @Test
  void theProbeUsesTheStoredSecretWhenNoneIsGiven() {
    OidcProvider provider = createProvider(keycloak.issuerUri());
    keycloak.withGroup("g-1", "Haus", "/Haus", null);
    save(provider, null, FakeKeycloakServer.CLIENT_ID, FakeKeycloakServer.CLIENT_SECRET);

    KeycloakDirectoryConnector.ProbeOutcome outcome =
        connectorService.probe(
            provider.getId(),
            DirectoryConnectorType.KEYCLOAK,
            null,
            FakeKeycloakServer.CLIENT_ID,
            null);

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.message()).contains(FakeKeycloakServer.REALM);
  }

  @Test
  void aProbeWithoutAStoredAccessNeedsTheSecret() {
    OidcProvider provider = createProvider(keycloak.issuerUri());

    assertThatThrownBy(
            () ->
                connectorService.probe(
                    provider.getId(),
                    DirectoryConnectorType.KEYCLOAK,
                    null,
                    FakeKeycloakServer.CLIENT_ID,
                    null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Geheimnis");
  }

  /** The behaviour the removed NoOpDirectoryClient had, now per provider. */
  @Test
  void aProviderWithoutAStoredAccessReportsTheDirectoryAsUnreachable() {
    OidcProvider provider = createProvider(keycloak.issuerUri());

    assertThatThrownBy(
            () -> providerDirectoryClient.fetchSnapshot(ORGANIZATION_ID, provider.getId()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("kein Verzeichniszugang");
  }

  @Test
  void theStoredAccessIsWhatTheRunReadsWith() throws Exception {
    OidcProvider provider = createProvider(keycloak.issuerUri());
    keycloak
        .withGroup("g-haus", "Haus", "/Haus", null)
        .withGroup("g-50", "Referat 50", "/Haus/Referat 50", "g-haus", "u-1");
    save(provider, null, FakeKeycloakServer.CLIENT_ID, FakeKeycloakServer.CLIENT_SECRET);

    DirectorySnapshot snapshot =
        providerDirectoryClient.fetchSnapshot(ORGANIZATION_ID, provider.getId());

    assertThat(snapshot.groups()).hasSize(2);
    assertThat(snapshot.groups())
        .anySatisfy(
            group -> {
              assertThat(group.externalId()).isEqualTo("g-50");
              assertThat(group.sourcePath()).isEqualTo("/Haus/Referat 50");
              assertThat(group.memberSubjects()).containsExactly("u-1");
            });
  }

  /** A wrong stored secret must not look like an empty directory - it revokes nothing. */
  @Test
  void aRejectedStoredSecretReportsTheDirectoryAsUnreachable() {
    OidcProvider provider = createProvider(keycloak.issuerUri());
    keycloak.withGroup("g-1", "Haus", "/Haus", null);
    save(provider, null, FakeKeycloakServer.CLIENT_ID, "falsch");

    assertThatThrownBy(
            () -> providerDirectoryClient.fetchSnapshot(ORGANIZATION_ID, provider.getId()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Dienstkonto");
  }

  // ---------------------------------------------------------------------------------------
  // A row this deployment's key cannot decrypt
  // ---------------------------------------------------------------------------------------

  /**
   * After a rotated encryption key or a restored backup the stored ciphertext no longer decrypts.
   * The provider list maps every row, so a hard read failure there would take the whole
   * Anbieterverwaltung down - including the two repair paths the handbook names.
   */
  @Test
  void anUndecryptableRowStillAppearsInTheListAndInItsOwnView() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());
    save(provider, null, "opaa-directory", "geheim");
    corruptStoredSecret(provider);

    assertThat(connectorService.viewsByProvider(ORGANIZATION_ID))
        .containsKey(provider.getId())
        .extractingByKey(provider.getId())
        .satisfies(view -> assertThat(view.clientId()).isEqualTo("opaa-directory"));
    assertThat(connectorService.findByProvider(provider.getId())).isPresent();
  }

  /** Repair path one of the handbook: remove the access. */
  @Test
  void anUndecryptableRowCanStillBeDeleted() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());
    save(provider, null, "opaa-directory", "geheim");
    corruptStoredSecret(provider);

    connectorService.delete(ORGANIZATION_ID, actorId, provider.getId());

    assertThat(connectorRepository.findByProviderId(provider.getId())).isEmpty();
  }

  /** Repair path two: store it again - the new value is encrypted with the current key. */
  @Test
  void anUndecryptableRowCanStillBeReplaced() {
    OidcProvider provider = createProvider("https://idp.example/realms/haus-" + UUID.randomUUID());
    save(provider, null, "opaa-directory", "geheim");
    corruptStoredSecret(provider);

    save(provider, null, "opaa-directory", "neu");

    assertThat(
            connectorRepository.findByProviderId(provider.getId()).orElseThrow().getClientSecret())
        .isEqualTo("neu");
  }

  @Test
  void aProbeAgainstAnUndecryptableRowSaysSoInsteadOfSigningInWithNothing() {
    OidcProvider provider = createProvider(keycloak.issuerUri());
    save(provider, null, FakeKeycloakServer.CLIENT_ID, FakeKeycloakServer.CLIENT_SECRET);
    corruptStoredSecret(provider);

    assertThatThrownBy(
            () ->
                connectorService.probe(
                    provider.getId(),
                    DirectoryConnectorType.KEYCLOAK,
                    null,
                    FakeKeycloakServer.CLIENT_ID,
                    null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nicht entschlüsseln");
  }

  /** The run keeps the last known good state: unreachable, never an empty directory. */
  @Test
  void aRunAgainstAnUndecryptableRowReportsTheDirectoryAsUnreachable() {
    OidcProvider provider = createProvider(keycloak.issuerUri());
    keycloak.withGroup("g-1", "Haus", "/Haus", null);
    save(provider, null, FakeKeycloakServer.CLIENT_ID, FakeKeycloakServer.CLIENT_SECRET);
    corruptStoredSecret(provider);

    assertThatThrownBy(
            () -> providerDirectoryClient.fetchSnapshot(ORGANIZATION_ID, provider.getId()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("nicht entschlüsseln");
  }

  // ---------------------------------------------------------------------------------------
  // The address policy at use time, not only at save time
  // ---------------------------------------------------------------------------------------

  /** A row edited past the application, or a narrowed allowlist, must not reach the network. */
  @Test
  void anAddressChangedPastTheApplicationIsRefusedAtUseTime() {
    OidcProvider provider = createProvider(keycloak.issuerUri());
    save(provider, null, FakeKeycloakServer.CLIENT_ID, FakeKeycloakServer.CLIENT_SECRET);
    jdbcTemplate.update(
        "UPDATE directory_connectors SET base_url = ? WHERE provider_id = ?",
        "http://192.168.7.7:8080",
        provider.getId());

    assertThatThrownBy(
            () -> providerDirectoryClient.fetchSnapshot(ORGANIZATION_ID, provider.getId()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Admin-API-Adresse");
  }

  @Test
  void anIssuerThatStoppedBeingARealmAddressIsRefusedAtUseTime() {
    OidcProvider provider = createProvider(keycloak.issuerUri());
    save(provider, null, FakeKeycloakServer.CLIENT_ID, FakeKeycloakServer.CLIENT_SECRET);
    jdbcTemplate.update(
        "UPDATE oidc_providers SET issuer_uri = ? WHERE id = ?",
        "https://entra.example/tenant/v2.0",
        provider.getId());

    assertThatThrownBy(
            () -> providerDirectoryClient.fetchSnapshot(ORGANIZATION_ID, provider.getId()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Keycloak-Realm-Adresse");
  }

  // ---------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------

  private DirectoryConnectorView save(
      OidcProvider provider, String baseUrl, String clientId, String secret) {
    return connectorService.save(
        ORGANIZATION_ID,
        actorId,
        provider.getId(),
        DirectoryConnectorType.KEYCLOAK,
        baseUrl,
        clientId,
        secret);
  }

  /** What a rotated key or a restored backup leaves behind: a blob with the right prefix. */
  private void corruptStoredSecret(OidcProvider provider) {
    jdbcTemplate.update(
        "UPDATE directory_connectors SET client_secret = ? WHERE provider_id = ?",
        "enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        provider.getId());
  }

  private String storedSecret(OidcProvider provider) {
    return jdbcTemplate.queryForObject(
        "SELECT client_secret FROM directory_connectors WHERE provider_id = ?",
        String.class,
        provider.getId());
  }

  /**
   * The one {@link ProviderType#LOCAL} row. Created here when the bootstrap seed left none (the
   * unique index allows exactly one), and then cleaned up like every other row this class creates.
   */
  private OidcProvider localProviderRow() {
    return providerRepository.findAll().stream()
        .filter(provider -> provider.getProviderType() == ProviderType.LOCAL)
        .findFirst()
        .orElseGet(
            () -> {
              OidcProvider local =
                  providerRepository.save(OidcProvider.localProvider("Lokale Konten"));
              createdProviderIds.add(local.getId());
              return local;
            });
  }

  private OidcProvider createProvider(String issuerUri) {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            issuerUri,
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    providerRepository.save(provider);
    createdProviderIds.add(provider.getId());
    return provider;
  }
}
