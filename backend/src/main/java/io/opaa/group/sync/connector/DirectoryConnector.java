package io.opaa.group.sync.connector;

import io.opaa.api.types.DirectoryConnectorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The access one provider's directory run signs in with (#1817, ADR-0036 Entscheidung 3). At most
 * one row per provider ({@code uk_directory_connectors_provider}) and gone with it ({@code ON
 * DELETE CASCADE}): a provider has exactly one directory, and a stored secret has no reason to
 * outlive the row it belongs to.
 *
 * <p><b>No realm column.</b> The realm a run reads is derived from the provider's own issuer URI
 * (see {@code KeycloakRealmAddress}), never configured here - a second, independently editable
 * value could drift from the issuer, and a provider group would then carry members of a foreign
 * issuer (ADR-0025). {@link #getBaseUrl()} is the backend-side override of the admin API address
 * only, the same split {@code jwk_set_uri} makes for the JWK set.
 *
 * <p><b>The secret is stored encrypted</b> by {@link DirectoryConnectorSecretConverter} ({@code
 * io.opaa.security.CredentialsEncryptor}, AES-256-GCM, {@code enc:v1:}). Rotating the encryption
 * key itself is not supported in place (a key change is expected to bring an {@code enc:v2:}
 * format); a house whose rules require the <em>service account's</em> password to be rotated
 * regularly can do so freely - that is a write through {@link DirectoryConnectorService}, which
 * re-encrypts with the current key - but must keep {@code OPAA_CREDENTIALS_ENCRYPTION_KEY} itself
 * stable. See {@code docs/handbuch/deployment.md}, "Keycloak als Verzeichnis".
 */
@Entity
@Table(name = "directory_connectors")
public class DirectoryConnector {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "provider_id", nullable = false)
  private UUID providerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "connector_type", nullable = false, length = 20)
  private DirectoryConnectorType connectorType;

  /** Null means "derive the admin API address from the provider's issuer URI". */
  @Column(name = "base_url", length = 500)
  private String baseUrl;

  @Column(name = "client_id", nullable = false, length = 255)
  private String clientId;

  @Convert(converter = DirectoryConnectorSecretConverter.class)
  @Column(name = "client_secret", nullable = false, length = 2000)
  private String clientSecret;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected DirectoryConnector() {}

  public DirectoryConnector(
      UUID organizationId,
      UUID providerId,
      DirectoryConnectorType connectorType,
      String baseUrl,
      String clientId,
      String clientSecret,
      Instant now) {
    this.id = UUID.randomUUID();
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    this.createdAt = now;
    replaceDetails(connectorType, baseUrl, clientId, clientSecret, now);
  }

  public void replaceDetails(
      DirectoryConnectorType connectorType,
      String baseUrl,
      String clientId,
      String clientSecret,
      Instant now) {
    this.connectorType = Objects.requireNonNull(connectorType, "connectorType");
    this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
    this.clientId = Objects.requireNonNull(clientId, "clientId").trim();
    this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getProviderId() {
    return providerId;
  }

  public DirectoryConnectorType getConnectorType() {
    return connectorType;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getClientId() {
    return clientId;
  }

  /** The decrypted secret - only ever handed to the connector that signs in with it. */
  public String getClientSecret() {
    return clientSecret;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
