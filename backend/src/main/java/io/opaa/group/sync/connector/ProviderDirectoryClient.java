package io.opaa.group.sync.connector;

import io.opaa.auth.oidc.OidcAddressPolicy;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ValidationException;
import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import io.opaa.group.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.group.sync.keycloak.KeycloakRealmAddress;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The productive {@link DirectoryClient} (#1817): it looks up the directory access stored for the
 * provider a run is bound to and hands the read to that access's connector - today {@link
 * KeycloakDirectoryConnector}, tomorrow LDAP and Microsoft Graph as further cases of {@link
 * io.opaa.api.types.DirectoryConnectorType} (ADR-0036, Entscheidung 3).
 *
 * <p><b>No stored access is "unreachable", never "no groups".</b> That is the behaviour the removed
 * {@code NoOpDirectoryClient} had for the whole installation, now per provider: the safe direction,
 * because the run then keeps the last known good state and revokes nothing (#237). The same holds
 * for every way the stored access cannot be used at all - an issuer that is no Keycloak realm
 * address, an address the policy no longer allows, a secret this deployment's key cannot decrypt.
 *
 * <p><b>The address policy is applied at use time, not only at save time</b>, exactly as {@code
 * OidcProviderRegistry} does for the issuer: a narrowed allowlist or a row edited directly in the
 * database must not reach the network.
 */
public class ProviderDirectoryClient implements DirectoryClient {

  private final DirectoryConnectorRepository connectors;
  private final OidcProviderRepository providers;
  private final OidcAddressPolicy addressPolicy;
  private final KeycloakDirectoryConnector keycloak;

  public ProviderDirectoryClient(
      DirectoryConnectorRepository connectors,
      OidcProviderRepository providers,
      OidcAddressPolicy addressPolicy,
      KeycloakDirectoryConnector keycloak) {
    this.connectors = connectors;
    this.providers = providers;
    this.addressPolicy = addressPolicy;
    this.keycloak = keycloak;
  }

  @Override
  public DirectorySnapshot fetchGroups(UUID organizationId, UUID providerId)
      throws DirectoryUnavailableException {
    DirectoryConnector connector =
        readConnector(providerId)
            .orElseThrow(
                () ->
                    new DirectoryUnavailableException(
                        "Für diesen Anbieter ist kein Verzeichniszugang hinterlegt."));
    OidcProvider provider =
        providers
            .findById(providerId)
            .orElseThrow(
                () ->
                    new DirectoryUnavailableException(
                        "Der Anbieter dieses Verzeichnisabgleichs existiert nicht mehr."));
    KeycloakRealmAddress address =
        asUnavailable(
            () -> {
              KeycloakRealmAddress resolved =
                  KeycloakRealmAddress.of(provider.getIssuerUri(), connector.getBaseUrl());
              addressPolicy.requireAllowed(resolved.baseUrl(), "Admin-API-Adresse");
              return resolved;
            });
    return keycloak.fetchGroups(address, connector.getClientId(), connector.getClientSecret());
  }

  private java.util.Optional<DirectoryConnector> readConnector(UUID providerId)
      throws DirectoryUnavailableException {
    try {
      return connectors.findByProviderId(providerId);
    } catch (CredentialsEncryptionKeyMissingException e) {
      throw new DirectoryUnavailableException(
          "Die hinterlegten Zugangsdaten des Verzeichnisses lassen sich nicht entschlüsseln:"
              + " "
              + e.getMessage(),
          e);
    }
  }

  private <T> T asUnavailable(Supplier<T> action) throws DirectoryUnavailableException {
    try {
      return action.get();
    } catch (ValidationException e) {
      throw new DirectoryUnavailableException(e.getMessage(), e);
    }
  }
}
