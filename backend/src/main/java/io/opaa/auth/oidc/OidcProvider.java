package io.opaa.auth.oidc;

import io.opaa.api.types.ProviderType;
import io.opaa.auth.LocalIssuer;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One identity provider a deployment accepts sign-ins from (#1329, ADR-0025). {@link
 * #getIssuerUri()} is the provider's identity: unique per row ({@code
 * uq_oidc_providers_issuer_uri}), the {@code iss} a token must carry, and the authority the SPA
 * runs the code flow against. Public clients only - there is no secret. {@link #getJwkSetUri()}
 * optionally names the backend-side address of the JWK set when it differs from what discovery
 * under the issuer would yield (the Compose split between {@code keycloak:8180} and {@code
 * localhost:8180}). At most one row is the default provider ({@code
 * ux_oidc_providers_single_default}), the one {@code opaa.auth.initial-admin-email} applies to.
 *
 * <p>Since ADR-0033 the local account management is one row of this table too ({@link
 * #localProvider}, {@link ProviderType#LOCAL}, at most one - {@code
 * ux_oidc_providers_single_local}): its {@link #isEnabled()} is that management's switch, its
 * issuer is fixed to {@link LocalIssuer#URN}, it has no client id, is never the default and has no
 * editable connection details - the schema's CHECKs and this class refuse all of that.
 */
@Entity
@Table(name = "oidc_providers")
public class OidcProvider {

  @Id private UUID id;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "is_default", nullable = false)
  private boolean defaultProvider;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "issuer_uri", nullable = false, length = 500)
  private String issuerUri;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider_type", nullable = false, length = 16)
  private ProviderType providerType = ProviderType.OIDC;

  /** {@code null} exactly for the {@link ProviderType#LOCAL} row. */
  @Column(name = "client_id", length = 255)
  private String clientId;

  @Column(name = "jwk_set_uri", length = 500)
  private String jwkSetUri;

  @Embedded private OidcClaimMapping claimMapping;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OidcProvider() {}

  public OidcProvider(
      String displayName,
      String issuerUri,
      String clientId,
      String jwkSetUri,
      OidcClaimMapping claimMapping) {
    this.id = UUID.randomUUID();
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
    replaceDetails(displayName, issuerUri, clientId, jwkSetUri, claimMapping);
  }

  /**
   * The one {@link ProviderType#LOCAL} row (ADR-0033, Entscheidung 4): switched off until an
   * administrator enables the local account management, never the default, fixed issuer, no client
   * id. Created by the bootstrap seed, never by the provider API.
   */
  public static OidcProvider localProvider(String displayName) {
    OidcProvider provider = new OidcProvider();
    provider.id = UUID.randomUUID();
    provider.createdAt = Instant.now();
    provider.updatedAt = provider.createdAt;
    provider.providerType = ProviderType.LOCAL;
    provider.displayName = Objects.requireNonNull(displayName, "displayName").trim();
    provider.issuerUri = LocalIssuer.URN;
    provider.clientId = null;
    provider.jwkSetUri = null;
    provider.claimMapping = OidcClaimMapping.keycloakDefaults();
    provider.enabled = false;
    provider.defaultProvider = false;
    return provider;
  }

  /**
   * Replaces every editable field - not {@code enabled}, {@code defaultProvider}, {@code
   * sortOrder}. Refused for the {@link ProviderType#LOCAL} row, whose only editable field is the
   * name ({@link #rename}).
   */
  public void replaceDetails(
      String displayName,
      String issuerUri,
      String clientId,
      String jwkSetUri,
      OidcClaimMapping claimMapping) {
    if (isLocal()) {
      throw new IllegalStateException(
          "the local provider row has no editable connection details (ADR-0033)");
    }
    this.displayName = Objects.requireNonNull(displayName, "displayName").trim();
    // stored as minted (ADR-0025): the decoder compares a token's iss with it byte for byte
    this.issuerUri = Objects.requireNonNull(issuerUri, "issuerUri").trim();
    this.clientId = Objects.requireNonNull(clientId, "clientId").trim();
    this.jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? null : jwkSetUri.trim();
    this.claimMapping = claimMapping == null ? OidcClaimMapping.keycloakDefaults() : claimMapping;
    this.updatedAt = Instant.now();
  }

  /** The only edit the {@link ProviderType#LOCAL} row allows; harmless for any other row. */
  public void rename(String displayName) {
    this.displayName = Objects.requireNonNull(displayName, "displayName").trim();
    this.updatedAt = Instant.now();
  }

  /**
   * Whether a decoder built for {@code other} verifies this row's tokens too - same type, issuer,
   * client id and JWK set address; the claim mapping plays no part in token verification.
   */
  public boolean hasSameDecoderInputsAs(OidcProvider other) {
    return providerType == other.providerType
        && issuerUri.equals(other.issuerUri)
        && Objects.equals(clientId, other.clientId)
        && Objects.equals(jwkSetUri, other.jwkSetUri);
  }

  public boolean isLocal() {
    return providerType == ProviderType.LOCAL;
  }

  public void enable() {
    this.enabled = true;
    this.updatedAt = Instant.now();
  }

  public void disable() {
    this.enabled = false;
    this.updatedAt = Instant.now();
  }

  /** Refused for the {@link ProviderType#LOCAL} row: only an OIDC row is the directory provider. */
  public void markDefault() {
    if (isLocal()) {
      throw new IllegalStateException("the local provider row is never the default (ADR-0033)");
    }
    this.defaultProvider = true;
    this.updatedAt = Instant.now();
  }

  public void clearDefault() {
    this.defaultProvider = false;
    this.updatedAt = Instant.now();
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isDefaultProvider() {
    return defaultProvider;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public String getIssuerUri() {
    return issuerUri;
  }

  public ProviderType getProviderType() {
    return providerType;
  }

  public String getClientId() {
    return clientId;
  }

  public String getJwkSetUri() {
    return jwkSetUri;
  }

  public OidcClaimMapping getClaimMapping() {
    return claimMapping;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof OidcProvider other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
