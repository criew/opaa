package io.opaa.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * When the provider of an issuer was last deleted, keyed by {@link OidcIssuerUris#normalize}. A
 * later deletion of a provider with the same issuer replaces the row; {@link AccountUsability}
 * reads it only while no provider holds the issuer.
 */
@Entity
@Table(name = "oidc_provider_removals")
public class OidcProviderRemoval {

  @Id
  @Column(name = "issuer_uri_normalized", length = 500)
  private String issuerUriNormalized;

  @Column(name = "removed_at", nullable = false)
  private Instant removedAt;

  protected OidcProviderRemoval() {}

  public OidcProviderRemoval(String issuerUri, Instant removedAt) {
    this.issuerUriNormalized = OidcIssuerUris.normalize(issuerUri);
    this.removedAt = removedAt;
  }

  public String getIssuerUriNormalized() {
    return issuerUriNormalized;
  }

  public Instant getRemovedAt() {
    return removedAt;
  }
}
