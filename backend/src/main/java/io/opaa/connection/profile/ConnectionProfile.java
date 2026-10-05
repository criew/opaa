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
import java.util.Objects;
import java.util.UUID;

/**
 * A connection profile ("Zugang"): the frame the administration sets for one connector - server
 * address, proxy and TLS switch, sign-in method, app registration, scopes and connector defaults -
 * or, of kind {@link ProfileKind#MCP_SERVER}, for an MCP server without a connector. The client
 * secret - for a service account key the key file - is held only as {@code CredentialsEncryptor}
 * ciphertext; only the profile administration and {@link ProfileRegistrations} read it.
 */
@Entity
@Table(name = "connection_profiles")
public class ConnectionProfile {

  @Id private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private ProfileKind kind;

  @Column(name = "source_type", length = SourceType.MAX_LENGTH)
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

  @Column(name = "client_secret_ciphertext")
  private String clientSecretCiphertext;

  @Column(name = "client_secret_expires_on")
  private LocalDate clientSecretExpiresOn;

  @Column(name = "client_secret_expiry_warned_at")
  private Instant clientSecretExpiryWarnedAt;

  @Column(name = "tenant")
  private String tenant;

  @Column(name = "scopes", length = 2000)
  private String scopes;

  @Column(name = "connector_settings")
  private String connectorSettings;

  @Column(name = "authorization_endpoint", length = 2000)
  private String authorizationEndpoint;

  @Column(name = "token_endpoint", length = 2000)
  private String tokenEndpoint;

  @Column(name = "revocation_endpoint", length = 2000)
  private String revocationEndpoint;

  @Column(name = "source_proxy")
  private String sourceProxy;

  @Column(name = "source_insecure_ssl", nullable = false)
  private boolean sourceInsecureSsl;

  @Column(name = "responsible_group_id")
  private UUID responsibleGroupId;

  @Column(name = "issuer", length = 2000)
  private String issuer;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Column(name = "sign_in_rejected_at")
  private Instant signInRejectedAt;

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
    this.kind = ProfileKind.CONNECTOR;
    this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** A new profile of kind {@link ProfileKind#MCP_SERVER}, without a source type. */
  static ConnectionProfile mcpServer(Instant now) {
    ConnectionProfile profile = new ConnectionProfile();
    profile.id = UUID.randomUUID();
    profile.kind = ProfileKind.MCP_SERVER;
    profile.createdAt = now;
    profile.updatedAt = now;
    return profile;
  }

  void replace(ConnectionProfileValues values, String clientSecretCiphertext, Instant now) {
    this.name = values.name();
    this.serverUrl = values.serverUrl();
    this.authMethod = values.authMethod();
    this.ownership = values.ownership();
    this.clientId = values.clientId();
    if (!Objects.equals(clientSecretCiphertext, this.clientSecretCiphertext)
        || !Objects.equals(values.clientSecretExpiresOn(), this.clientSecretExpiresOn)) {
      // a new secret or a new date is warned of anew
      this.clientSecretExpiryWarnedAt = null;
    }
    this.clientSecretCiphertext = clientSecretCiphertext;
    this.clientSecretExpiresOn = values.clientSecretExpiresOn();
    this.tenant = values.tenant();
    this.scopes = values.scopes();
    this.connectorSettings =
        values.connectorSettings() == null ? null : values.connectorSettings().toJson();
    this.sourceProxy = values.sourceProxy();
    this.sourceInsecureSsl = values.sourceInsecureSsl();
    this.authorizationEndpoint = values.endpoints().authorization();
    this.tokenEndpoint = values.endpoints().token();
    this.revocationEndpoint = values.endpoints().revocation();
    this.updatedAt = now;
  }

  /** A copy of this profile carrying {@code values}, to see what a change would do; never saved. */
  ConnectionProfile candidate(ConnectionProfileValues values) {
    ConnectionProfile copy = new ConnectionProfile();
    copy.id = id;
    copy.kind = kind;
    copy.sourceType = sourceType;
    copy.lockedAt = lockedAt;
    copy.signInRejectedAt = signInRejectedAt;
    copy.createdAt = createdAt;
    copy.replace(values, clientSecretCiphertext, updatedAt);
    return copy;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public ProfileKind getKind() {
    return kind == null ? ProfileKind.CONNECTOR : kind;
  }

  public boolean isMcpServer() {
    return getKind() == ProfileKind.MCP_SERVER;
  }

  /** The connector's type; {@code null} exactly for an MCP server. */
  public SourceType getSourceType() {
    return sourceType;
  }

  /** The group answering for an MCP server, {@code null} for none or a connector profile. */
  public UUID getResponsibleGroupId() {
    return responsibleGroupId;
  }

  /** The issuer of an MCP server's authorization server as discovered when it was saved. */
  public String getIssuer() {
    return issuer;
  }

  void mcpServerFrame(UUID responsibleGroupId, String issuer) {
    this.responsibleGroupId = responsibleGroupId;
    this.issuer = issuer;
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

  /** When the system administration was warned of the secret's expiry date; {@code null} if not. */
  public Instant getClientSecretExpiryWarnedAt() {
    return clientSecretExpiryWarnedAt;
  }

  public String getTenant() {
    return tenant;
  }

  public String getScopes() {
    return scopes;
  }

  /** The endpoints the profile names itself; each {@code null} where it names none. */
  public ProfileEndpoints getEndpoints() {
    return new ProfileEndpoints(authorizationEndpoint, tokenEndpoint, revocationEndpoint);
  }

  /** The connector defaults as stored JSON, {@code null} for none. */
  public String getConnectorSettings() {
    return connectorSettings;
  }

  /** The proxy every library on the profile is reached through, {@code null} for none. */
  public String getSourceProxy() {
    return sourceProxy;
  }

  /** Whether the certificate check is skipped for every library on the profile. */
  public boolean isSourceInsecureSsl() {
    return sourceInsecureSsl;
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

  /**
   * Since when the provider rejects the profile's own sign-in, {@code null} while it does not; a
   * new secret or a successful sign-in test lifts it.
   */
  public Instant getSignInRejectedAt() {
    return signInRejectedAt;
  }

  public boolean isSignInRejected() {
    return signInRejectedAt != null;
  }

  void signInRejectedSince(Instant at) {
    this.signInRejectedAt = at;
  }

  /** Drops the client secret or key; a rejection of it goes with it. */
  void dropClientSecret(Instant now) {
    this.clientSecretCiphertext = null;
    this.signInRejectedAt = null;
    this.updatedAt = now;
  }

  /** Grows with every change of the row; a flow started on one version ends on the same. */
  public long getVersion() {
    return version == null ? 0 : version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
