package io.opaa.connection.oauth;

import io.opaa.api.types.ConnectionAuthorizationPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An OAuth consent a person started: the hash of its state, its PKCE verifier as {@code
 * CredentialsEncryptor} ciphertext and the profile version it is bound to, for one completion
 * before {@link #getExpiresAt}. {@link #toString} shows neither.
 */
@Entity
@Table(name = "connection_authorizations")
class ConnectionAuthorization {

  @Id private UUID id;

  @Column(name = "state_hash", nullable = false, length = 64)
  private String stateHash;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "profile_id", nullable = false)
  private UUID profileId;

  @Column(name = "profile_version", nullable = false)
  private long profileVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 20)
  private ConnectionAuthorizationPurpose purpose;

  @Column(name = "library_id")
  private UUID libraryId;

  @Column(name = "code_verifier_ciphertext", nullable = false)
  private String codeVerifierCiphertext;

  @Column(name = "redirect_uri", nullable = false, length = 2000)
  private String redirectUri;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected ConnectionAuthorization() {}

  ConnectionAuthorization(
      String stateHash,
      UUID userId,
      UUID profileId,
      long profileVersion,
      ConnectionAuthorizationPurpose purpose,
      String codeVerifierCiphertext,
      String redirectUri,
      Instant now,
      Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.stateHash = stateHash;
    this.userId = userId;
    this.profileId = profileId;
    this.profileVersion = profileVersion;
    this.purpose = purpose;
    this.codeVerifierCiphertext = codeVerifierCiphertext;
    this.redirectUri = redirectUri;
    this.createdAt = now;
    this.expiresAt = expiresAt;
  }

  UUID getId() {
    return id;
  }

  UUID getUserId() {
    return userId;
  }

  UUID getProfileId() {
    return profileId;
  }

  long getProfileVersion() {
    return profileVersion;
  }

  ConnectionAuthorizationPurpose getPurpose() {
    return purpose;
  }

  String getCodeVerifierCiphertext() {
    return codeVerifierCiphertext;
  }

  String getRedirectUri() {
    return redirectUri;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }

  Instant getConsumedAt() {
    return consumedAt;
  }

  @Override
  public String toString() {
    return "ConnectionAuthorization[id=" + id + ", purpose=" + purpose + ", state=***]";
  }
}
