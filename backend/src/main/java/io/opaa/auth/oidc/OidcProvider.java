package io.opaa.auth.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
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

  @Column(name = "client_id", nullable = false, length = 255)
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
   * Replaces every editable field - not {@code enabled}, {@code defaultProvider}, {@code
   * sortOrder}.
   */
  public void replaceDetails(
      String displayName,
      String issuerUri,
      String clientId,
      String jwkSetUri,
      OidcClaimMapping claimMapping) {
    this.displayName = Objects.requireNonNull(displayName, "displayName").trim();
    // stored as minted (ADR-0025): the decoder compares a token's iss with it byte for byte
    this.issuerUri = Objects.requireNonNull(issuerUri, "issuerUri").trim();
    this.clientId = Objects.requireNonNull(clientId, "clientId").trim();
    this.jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? null : jwkSetUri.trim();
    this.claimMapping = claimMapping == null ? OidcClaimMapping.keycloakDefaults() : claimMapping;
    this.updatedAt = Instant.now();
  }

  /**
   * Whether a decoder built for {@code other} verifies this row's tokens too - same issuer, client
   * id and JWK set address; the claim mapping plays no part in token verification.
   */
  public boolean hasSameDecoderInputsAs(OidcProvider other) {
    return issuerUri.equals(other.issuerUri)
        && clientId.equals(other.clientId)
        && Objects.equals(jwkSetUri, other.jwkSetUri);
  }

  public void enable() {
    this.enabled = true;
    this.updatedAt = Instant.now();
  }

  public void disable() {
    this.enabled = false;
    this.updatedAt = Instant.now();
  }

  public void markDefault() {
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
