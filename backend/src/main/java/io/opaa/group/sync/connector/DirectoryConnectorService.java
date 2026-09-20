package io.opaa.group.sync.connector;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.DirectoryConnectorType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.oidc.OidcAddressPolicy;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.group.sync.keycloak.KeycloakRealmAddress;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores, removes and probes the directory access of one identity provider (#1817, ADR-0036
 * Entscheidung 3) - the write side of {@link ProviderDirectoryClient}'s read.
 *
 * <p><b>The realm is never an input.</b> It is derived from the provider's own issuer URI, so a run
 * structurally reads the realm this provider's tokens come from (ADR-0025). A provider whose issuer
 * is no Keycloak realm address therefore cannot get a Keycloak connector at all, and says so.
 *
 * <p><b>The secret leaves this class only downwards.</b> {@link DirectoryConnectorView}, which
 * every response is built from, has no field for it; the entity's converter encrypts it before the
 * database sees it, and no audit entry carries it either.
 */
@Service
public class DirectoryConnectorService {

  /** The 409 code when the provider has no directory at all - the LOCAL row (ADR-0033). */
  public static final String NO_DIRECTORY_CODE = "DIRECTORY_CONNECTOR_NOT_APPLICABLE";

  private static final String ADDRESS_LABEL = "Admin-API-Adresse";

  private final DirectoryConnectorRepository repository;
  private final OidcProviderRepository providers;
  private final OidcAddressPolicy addressPolicy;
  private final KeycloakDirectoryConnector keycloak;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  public DirectoryConnectorService(
      DirectoryConnectorRepository repository,
      OidcProviderRepository providers,
      OidcAddressPolicy addressPolicy,
      KeycloakDirectoryConnector keycloak,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.repository = repository;
    this.providers = providers;
    this.addressPolicy = addressPolicy;
    this.keycloak = keycloak;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /** The provider's stored access, or empty - what the provider response carries. */
  @Transactional(readOnly = true)
  public Optional<DirectoryConnectorView> findByProvider(UUID providerId) {
    OidcProvider provider = requireProvider(providerId);
    return repository.findByProviderId(providerId).map(connector -> toView(connector, provider));
  }

  /**
   * Every stored access of the installation, keyed by provider - what the provider list needs
   * without asking per row.
   */
  @Transactional(readOnly = true)
  public Map<UUID, DirectoryConnectorView> viewsByProvider(UUID organizationId) {
    Map<UUID, OidcProvider> providerById = new HashMap<>();
    providers.findAll().forEach(provider -> providerById.put(provider.getId(), provider));
    Map<UUID, DirectoryConnectorView> views = new HashMap<>();
    for (DirectoryConnector connector : repository.findByOrganizationId(organizationId)) {
      OidcProvider provider = providerById.get(connector.getProviderId());
      if (provider != null) {
        views.put(connector.getProviderId(), toView(connector, provider));
      }
    }
    return views;
  }

  /**
   * Stores or replaces the access, secret included. Every call re-encrypts with the current key.
   */
  @Transactional
  public DirectoryConnectorView save(
      UUID organizationId,
      UUID actorUserId,
      UUID providerId,
      DirectoryConnectorType type,
      String baseUrl,
      String clientId,
      String clientSecret) {
    OidcProvider provider = requireProviderWithDirectory(providerId);
    requireKeycloak(type);
    KeycloakRealmAddress address = requireAllowedAddress(provider, baseUrl);
    Optional<DirectoryConnector> existing = repository.findByProviderId(providerId);
    Map<String, Object> before =
        existing.map(DirectoryConnectorService::auditState).orElseGet(HashMap::new);
    DirectoryConnector connector =
        existing
            .map(
                found -> {
                  found.replaceDetails(type, baseUrl, clientId, clientSecret, clock.instant());
                  return found;
                })
            .orElseGet(
                () ->
                    new DirectoryConnector(
                        organizationId,
                        providerId,
                        type,
                        baseUrl,
                        clientId,
                        clientSecret,
                        clock.instant()));
    repository.save(connector);
    record(
        organizationId,
        actorUserId,
        provider,
        before,
        auditState(connector),
        "Verzeichniszugang gespeichert (Realm „" + address.realm() + "“)");
    return toView(connector, provider);
  }

  /** Removes the access and its secret. Removing an absent one is not an error. */
  @Transactional
  public void delete(UUID organizationId, UUID actorUserId, UUID providerId) {
    OidcProvider provider = requireProvider(providerId);
    Optional<DirectoryConnector> existing = repository.findByProviderId(providerId);
    if (existing.isEmpty()) {
      return;
    }
    repository.delete(existing.get());
    record(
        organizationId,
        actorUserId,
        provider,
        auditState(existing.get()),
        new HashMap<>(),
        "Verzeichniszugang entfernt");
  }

  /**
   * Probes the access without storing anything. A blank secret means "use the stored one", so an
   * existing connection can be re-tested without retyping a secret no response ever returns.
   */
  @Transactional(readOnly = true)
  public KeycloakDirectoryConnector.ProbeOutcome probe(
      UUID providerId,
      DirectoryConnectorType type,
      String baseUrl,
      String clientId,
      String secret) {
    OidcProvider provider = requireProviderWithDirectory(providerId);
    requireKeycloak(type);
    KeycloakRealmAddress address = requireAllowedAddress(provider, baseUrl);
    String effectiveSecret = secret;
    if (effectiveSecret == null || effectiveSecret.isBlank()) {
      effectiveSecret =
          repository
              .findByProviderId(providerId)
              .map(DirectoryConnector::getClientSecret)
              .orElseThrow(
                  () ->
                      new ValidationException(
                          "Für diesen Anbieter ist kein Verzeichniszugang hinterlegt; das"
                              + " Geheimnis des Dienstkontos ist deshalb anzugeben."));
    }
    return keycloak.probe(address, clientId, effectiveSecret);
  }

  private OidcProvider requireProvider(UUID providerId) {
    return providers
        .findById(providerId)
        .orElseThrow(() -> new NotFoundException("Anbieter nicht gefunden: " + providerId));
  }

  private OidcProvider requireProviderWithDirectory(UUID providerId) {
    OidcProvider provider = requireProvider(providerId);
    if (provider.isLocal()) {
      throw new ConflictException(
          "Die Zeile der lokalen Konten hat kein Verzeichnis. Lokale Konten werden Mitglied"
              + " interner Gruppen.",
          NO_DIRECTORY_CODE);
    }
    return provider;
  }

  private KeycloakRealmAddress requireAllowedAddress(OidcProvider provider, String baseUrl) {
    KeycloakRealmAddress address = KeycloakRealmAddress.of(provider.getIssuerUri(), baseUrl);
    addressPolicy.requireAllowed(address.baseUrl(), ADDRESS_LABEL);
    return address;
  }

  private void requireKeycloak(DirectoryConnectorType type) {
    if (type != DirectoryConnectorType.KEYCLOAK) {
      throw new ValidationException("Für diesen Verzeichnistyp gibt es noch keinen Konnektor.");
    }
  }

  private DirectoryConnectorView toView(DirectoryConnector connector, OidcProvider provider) {
    KeycloakRealmAddress address =
        KeycloakRealmAddress.of(provider.getIssuerUri(), connector.getBaseUrl());
    return new DirectoryConnectorView(
        connector.getConnectorType(),
        address.baseUrl(),
        address.realm(),
        connector.getClientId(),
        connector.getUpdatedAt());
  }

  /** Never the secret - only which account and where, so an entry stays readable in a log. */
  private static Map<String, Object> auditState(DirectoryConnector connector) {
    Map<String, Object> state = new HashMap<>();
    state.put("connectorType", connector.getConnectorType().name());
    state.put("clientId", connector.getClientId());
    if (connector.getBaseUrl() != null) {
      state.put("baseUrl", connector.getBaseUrl());
    }
    return state;
  }

  private void record(
      UUID organizationId,
      UUID actorUserId,
      OidcProvider provider,
      Map<String, Object> before,
      Map<String, Object> after,
      String reason) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.OIDC_PROVIDER_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                provider.getId(),
                "Identitätsanbieter „" + provider.getDisplayName() + "“")
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .reason(reason)
            .build());
  }
}
