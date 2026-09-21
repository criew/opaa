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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

/**
 * The productive {@link DirectoryClient} (#1817): it looks up the directory access stored for the
 * provider a run is bound to and hands the read to that access's connector - today {@link
 * KeycloakDirectoryConnector}, tomorrow LDAP and Microsoft Graph as further cases of {@link
 * io.opaa.api.types.DirectoryConnectorType} (ADR-0036, Entscheidung 3).
 *
 * <p><b>Every failure of this method is a {@link DirectoryUnavailableException}</b>, the declared
 * one, never an unchecked one: no stored access, an issuer that is no Keycloak realm address, an
 * address the policy no longer allows, a secret this deployment's key cannot decrypt, and any
 * failure of the database read itself. That is the behaviour the removed {@code
 * NoOpDirectoryClient} had for the whole installation, now per provider - and it is load-bearing in
 * both directions: {@code DirectorySyncService} catches only the declared exception, so anything
 * else would skip the {@code UNREACHABLE} outcome, the audit entry and the status line, and leave
 * the scheduled run due again one minute later, forever.
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
  public DirectorySnapshot fetchSnapshot(UUID organizationId, UUID providerId)
      throws DirectoryUnavailableException {
    DirectoryConnector connector =
        readFromDatabase(() -> connectors.findByProviderId(providerId))
            .orElseThrow(
                () ->
                    new DirectoryUnavailableException(
                        "Für diesen Anbieter ist kein Verzeichniszugang hinterlegt."));
    OidcProvider provider =
        readFromDatabase(() -> providers.findById(providerId))
            .orElseThrow(
                () ->
                    new DirectoryUnavailableException(
                        "Der Anbieter dieses Verzeichnisabgleichs existiert nicht mehr."));
    // null exactly when the stored ciphertext could not be decrypted - see
    // DirectoryConnectorSecretConverter. Signing in without it would be a sign-in as nobody.
    if (connector.getClientSecret() == null || connector.getClientSecret().isBlank()) {
      throw new DirectoryUnavailableException(
          "Das hinterlegte Geheimnis des Dienstkontos lässt sich nicht entschlüsseln. Hinterlegen"
              + " Sie den Verzeichniszugang erneut.");
    }
    KeycloakRealmAddress address =
        asUnavailable(
            () -> {
              KeycloakRealmAddress resolved =
                  KeycloakRealmAddress.of(provider.getIssuerUri(), connector.getBaseUrl());
              addressPolicy.requireAllowed(resolved.baseUrl(), "Admin-API-Adresse");
              return resolved;
            });
    return keycloak.fetchSnapshot(address, connector.getClientId(), connector.getClientSecret());
  }

  /**
   * Spring wraps every persistence failure into a {@link DataAccessException} on its way out of the
   * repository proxy, so that - not the cause type - is what this layer can catch. The most
   * specific cause supplies the message, because the wrapper's own text names the query, not the
   * problem.
   */
  private <T> Optional<T> readFromDatabase(Supplier<Optional<T>> read)
      throws DirectoryUnavailableException {
    try {
      return read.get();
    } catch (DataAccessException e) {
      Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
      throw new DirectoryUnavailableException(
          "Der hinterlegte Verzeichniszugang konnte nicht gelesen werden: " + cause.getMessage(),
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
