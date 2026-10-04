package io.opaa.connection.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored secret, bound to its profile and to the target it was issued for. The secret is held
 * only as {@code CredentialsEncryptor} ciphertext and decrypted only in {@link ConnectionSecrets};
 * {@link #toString} shows no value.
 */
@Entity
@Table(name = "connection_tokens")
class ConnectionToken {

  /** What the row holds: a personal secret, or an OAuth refresh token with its access token. */
  enum Kind {
    PERSONAL_SECRET,
    OAUTH
  }

  @Id private UUID id;

  @Column(name = "profile_id", nullable = false)
  private UUID profileId;

  @Column(name = "connected_account_id")
  private UUID connectedAccountId;

  @Column(name = "library_id")
  private UUID libraryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private Kind kind;

  @Column(name = "secret_ciphertext", nullable = false)
  private String secretCiphertext;

  @Column(name = "access_token_ciphertext")
  private String accessTokenCiphertext;

  @Column(name = "access_token_expires_at")
  private Instant accessTokenExpiresAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "issued_for", nullable = false, length = 2000)
  private String issuedFor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected ConnectionToken() {}

  /** A person's personal secret on the account {@code connectedAccountId}. */
  static ConnectionToken ofAccount(
      UUID profileId,
      UUID connectedAccountId,
      String secretCiphertext,
      Instant expiresAt,
      String issuedFor,
      Instant now) {
    ConnectionToken token = new ConnectionToken();
    token.id = UUID.randomUUID();
    token.profileId = profileId;
    token.connectedAccountId = connectedAccountId;
    token.kind = Kind.PERSONAL_SECRET;
    token.secretCiphertext = secretCiphertext;
    token.expiresAt = expiresAt;
    token.issuedFor = issuedFor;
    token.createdAt = now;
    token.updatedAt = now;
    return token;
  }

  /** Replaces the secret and its binding, as a reconnection does. */
  void replace(String secretCiphertext, Instant expiresAt, String issuedFor, Instant now) {
    this.kind = Kind.PERSONAL_SECRET;
    this.secretCiphertext = secretCiphertext;
    this.accessTokenCiphertext = null;
    this.accessTokenExpiresAt = null;
    this.expiresAt = expiresAt;
    this.issuedFor = issuedFor;
    this.updatedAt = now;
  }

  /** The provider rejected the secret: it ends now, and only a reconnection replaces it. */
  void endedAt(Instant now) {
    this.expiresAt = now;
    this.updatedAt = now;
  }

  UUID getProfileId() {
    return profileId;
  }

  UUID getConnectedAccountId() {
    return connectedAccountId;
  }

  Kind getKind() {
    return kind;
  }

  String getSecretCiphertext() {
    return secretCiphertext;
  }

  String getIssuedFor() {
    return issuedFor;
  }

  /** Whether the secret is past the end the provider named or the rejection set. */
  boolean expiredAt(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }

  @Override
  public String toString() {
    return "ConnectionToken[id=" + id + ", kind=" + kind + ", secret=***]";
  }
}
