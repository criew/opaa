package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.knowledge.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A connection profile ("Zugang"): the frame the administration sets for one connector - server
 * address, sign-in method, app registration, scopes and connector defaults. The client secret is
 * held only as {@code CredentialsEncryptor} ciphertext and never leaves {@link
 * ConnectionProfileService}.
 */
@Entity
@Table(name = "connection_profiles")
public class ConnectionProfile {

  @Id private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "source_type", nullable = false, length = SourceType.MAX_LENGTH)
  private SourceType sourceType;

  @Column(name = "server_url", nullable = false, length = 2000)
  private String serverUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "auth_method", nullable = false, length = 32)
  private ConnectionAuthMethod authMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "ownership", nullable = false, length = 16)
  private ConnectionOwnership ownership;

  @Column(name = "client_id")
  private String clientId;

  @Column(name = "client_secret_ciphertext", length = 3000)
  private String clientSecretCiphertext;

  @Column(name = "client_secret_expires_on")
  private LocalDate clientSecretExpiresOn;

  @Column(name = "tenant")
  private String tenant;

  @Column(name = "scopes", length = 2000)
  private String scopes;

  @Column(name = "connector_settings")
  private String connectorSettings;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected ConnectionProfile() {}

  public ConnectionProfile(SourceType sourceType, Instant now) {
    this.id = UUID.randomUUID();
    this.sourceType = sourceType;
    this.createdAt = now;
    this.updatedAt = now;
  }

  void replace(ConnectionProfileValues values, String clientSecretCiphertext, Instant now) {
    this.name = values.name();
    this.serverUrl = values.serverUrl();
    this.authMethod = values.authMethod();
    this.ownership = values.ownership();
    this.clientId = values.clientId();
    this.clientSecretCiphertext = clientSecretCiphertext;
    this.clientSecretExpiresOn = values.clientSecretExpiresOn();
    this.tenant = values.tenant();
    this.scopes = values.scopes();
    this.connectorSettings =
        values.connectorSettings() == null ? null : values.connectorSettings().toJson();
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public SourceType getSourceType() {
    return sourceType;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  public ConnectionAuthMethod getAuthMethod() {
    return authMethod;
  }

  public ConnectionOwnership getOwnership() {
    return ownership;
  }

  public String getClientId() {
    return clientId;
  }

  /** Whether a client secret is stored - the only thing an answer says about it. */
  public boolean isClientSecretSet() {
    return clientSecretCiphertext != null;
  }

  String getClientSecretCiphertext() {
    return clientSecretCiphertext;
  }

  public LocalDate getClientSecretExpiresOn() {
    return clientSecretExpiresOn;
  }

  public String getTenant() {
    return tenant;
  }

  public String getScopes() {
    return scopes;
  }

  /** The connector defaults as stored JSON, {@code null} for none. */
  public String getConnectorSettings() {
    return connectorSettings;
  }

  /** Since when the profile is locked, {@code null} while it is not. */
  public Instant getLockedAt() {
    return lockedAt;
  }

  public boolean isLocked() {
    return lockedAt != null;
  }

  void lockedSince(Instant at, Instant now) {
    this.lockedAt = at;
    this.updatedAt = now;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
