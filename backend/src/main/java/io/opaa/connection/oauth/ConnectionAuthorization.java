package io.opaa.connection.oauth;

import io.opaa.api.types.ConnectionAuthorizationPurpose;
import io.opaa.connection.profile.LibraryConnection.Responsible;
import io.opaa.connection.profile.LibraryConnection.ResponsibleType;
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
 * before {@link #getExpiresAt}. A consent for a library's source records when the caller confirmed
 * a service account and, for a reconnection, the library, who answers for it and whether a changed
 * account is confirmed. Where the profile knows its authorization server's issuer, the consent
 * keeps it as of the start, with whether the response must name it (RFC 9207). {@link #toString}
 * shows neither secret.
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

  @Column(name = "service_account_confirmed_at")
  private Instant serviceAccountConfirmedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "responsible_type", length = 10)
  private ResponsibleType responsibleType;

  @Column(name = "responsible_id")
  private UUID responsibleId;

  @Column(name = "account_change_confirmed_at")
  private Instant accountChangeConfirmedAt;

  @Column(name = "expected_issuer", length = 2000)
  private String expectedIssuer;

  @Column(name = "issuer_required")
  private Boolean issuerRequired;

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

  /**
   * Binds a consent for a library's source: the caller confirmed a service account at {@code
   * confirmedAt}; a reconnection names {@code libraryId}, {@code responsible} where it changes and
   * whether a changed account at the provider is accepted.
   */
  void forLibrary(
      Instant confirmedAt, UUID libraryId, Responsible responsible, boolean acceptsAccountChange) {
    this.serviceAccountConfirmedAt = confirmedAt;
    this.libraryId = libraryId;
    if (responsible != null) {
      this.responsibleType = responsible.type();
      this.responsibleId = responsible.id();
    }
    this.accountChangeConfirmedAt = acceptsAccountChange ? confirmedAt : null;
  }

  /**
   * Binds the consent to the authorization server {@code issuer} of the profile at the start;
   * {@code required} where that server announced the {@code iss} parameter (RFC 9207).
   */
  void expectIssuer(String issuer, boolean required) {
    this.expectedIssuer = issuer;
    this.issuerRequired = issuer != null && required;
  }

  /** The issuer the provider's response must not contradict, {@code null} where none is known. */
  String getExpectedIssuer() {
    return expectedIssuer;
  }

  /** Whether the provider's response must name {@link #getExpectedIssuer}. */
  boolean isIssuerRequired() {
    return Boolean.TRUE.equals(issuerRequired);
  }

  UUID getLibraryId() {
    return libraryId;
  }

  Instant getServiceAccountConfirmedAt() {
    return serviceAccountConfirmedAt;
  }

  /** Who answers for the library's consent after a reconnection, {@code null} to keep it. */
  Responsible getResponsible() {
    return responsibleType == null ? null : new Responsible(responsibleType, responsibleId);
  }

  boolean acceptsAccountChange() {
    return accountChangeConfirmedAt != null;
  }

  @Override
  public String toString() {
    return "ConnectionAuthorization[id=" + id + ", purpose=" + purpose + ", state=***]";
  }
}
