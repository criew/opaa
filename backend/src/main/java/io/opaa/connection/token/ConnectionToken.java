package io.opaa.connection.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored secret, bound to its profile and to the target it was issued for. The secret is held
 * only as {@code CredentialsEncryptor} ciphertext and decrypted only in {@link ConnectionSecrets};
 * {@link #toString} shows no value.
 */
@Entity
@Table(name = "connection_tokens")
class ConnectionToken {

  /** How long before the end the provider names its owner is warned. */
  static final Duration EXPIRY_WARNING = Duration.ofDays(14);

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

  @Column(name = "pending_user_id")
  private UUID pendingUserId;

  @Column(name = "pending_expires_at")
  private Instant pendingExpiresAt;

  @Column(name = "pending_account_label", length = 500)
  private String pendingAccountLabel;

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

  @Column(name = "expiry_warned_at")
  private Instant expiryWarnedAt;

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

  /** A person's OAuth grant on the account {@code connectedAccountId}. */
  static ConnectionToken ofAccountGrant(
      UUID profileId, UUID connectedAccountId, Ciphered grant, String issuedFor, Instant now) {
    ConnectionToken token =
        ofAccount(profileId, connectedAccountId, grant.refresh(), null, issuedFor, now);
    token.replaceGrant(grant, issuedFor, now);
    return token;
  }

  /** A library's own OAuth grant ("Quelle verbinden"). */
  static ConnectionToken ofLibraryGrant(
      UUID profileId, UUID libraryId, Ciphered grant, String issuedFor, Instant now) {
    ConnectionToken token = new ConnectionToken();
    token.id = UUID.randomUUID();
    token.profileId = profileId;
    token.libraryId = libraryId;
    token.createdAt = now;
    token.replaceGrant(grant, issuedFor, now);
    return token;
  }

  /**
   * An OAuth grant {@code userId} obtained as {@code accountLabel} for a library not created yet,
   * held until {@code until}.
   */
  static ConnectionToken pendingGrant(
      UUID profileId,
      UUID userId,
      String accountLabel,
      Ciphered grant,
      String issuedFor,
      Instant now,
      Instant until) {
    ConnectionToken token = new ConnectionToken();
    token.id = UUID.randomUUID();
    token.profileId = profileId;
    token.pendingUserId = userId;
    token.pendingExpiresAt = until;
    token.pendingAccountLabel = accountLabel;
    token.createdAt = now;
    token.replaceGrant(grant, issuedFor, now);
    return token;
  }

  /**
   * The library {@code libraryId} takes the pending grant over; it is the library's from now on.
   */
  void takenOverBy(UUID libraryId, Instant now) {
    this.libraryId = libraryId;
    this.pendingUserId = null;
    this.pendingExpiresAt = null;
    this.pendingAccountLabel = null;
    this.updatedAt = now;
  }

  /** Replaces the secret with an OAuth grant and its binding, as a reconnection does. */
  void replaceGrant(Ciphered grant, String issuedFor, Instant now) {
    this.kind = Kind.OAUTH;
    this.secretCiphertext = grant.refresh();
    this.accessTokenCiphertext = grant.access();
    this.accessTokenExpiresAt = grant.accessExpiresAt();
    consentEndsAt(grant.expiresAt(), now);
    this.issuedFor = issuedFor;
    this.updatedAt = now;
  }

  /**
   * A renewal obtained a new access token; a rotated refresh token replaces the stored one with its
   * end, else both stay.
   */
  void renewed(
      String accessCiphertext,
      Instant accessExpiresAt,
      String rotatedRefreshCiphertext,
      Instant rotatedExpiresAt,
      Instant now) {
    this.accessTokenCiphertext = accessCiphertext;
    this.accessTokenExpiresAt = accessExpiresAt;
    if (rotatedRefreshCiphertext != null) {
      this.secretCiphertext = rotatedRefreshCiphertext;
      rotatedEndsAt(rotatedExpiresAt, now);
    }
    this.updatedAt = now;
  }

  /** Replaces the secret and its binding, as a reconnection does. */
  void replace(String secretCiphertext, Instant expiresAt, String issuedFor, Instant now) {
    this.kind = Kind.PERSONAL_SECRET;
    this.secretCiphertext = secretCiphertext;
    this.accessTokenCiphertext = null;
    this.accessTokenExpiresAt = null;
    this.expiresAt = expiresAt;
    this.expiryWarnedAt = null;
    this.issuedFor = issuedFor;
    this.updatedAt = now;
  }

  /** The provider rejected the secret: it ends now, and only a reconnection replaces it. */
  void endedAt(Instant now) {
    this.expiresAt = now;
    this.updatedAt = now;
  }

  /**
   * A new consent's end is warned of once; an end the provider names already within {@link
   * #EXPIRY_WARNING} is a lifetime shorter than the warning and is never warned of.
   */
  private void consentEndsAt(Instant end, Instant now) {
    this.expiryWarnedAt = withinWarning(end, now) ? now : null;
    this.expiresAt = end;
  }

  /**
   * A rotation that moves the end out of {@link #EXPIRY_WARNING} is warned of anew; one that keeps
   * it within, as a sliding idle end does on every renewal, keeps the marker, so no end warns
   * daily.
   */
  private void rotatedEndsAt(Instant end, Instant now) {
    if (!Objects.equals(end, this.expiresAt) && !withinWarning(end, now)) {
      this.expiryWarnedAt = null;
    }
    this.expiresAt = end;
  }

  private static boolean withinWarning(Instant end, Instant now) {
    return end != null && end.isBefore(now.plus(EXPIRY_WARNING));
  }

  /** The owner was warned of the end the provider named. */
  void expiryWarned(Instant now) {
    this.expiryWarnedAt = now;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }

  Instant getExpiryWarnedAt() {
    return expiryWarnedAt;
  }

  UUID getProfileId() {
    return profileId;
  }

  UUID getConnectedAccountId() {
    return connectedAccountId;
  }

  UUID getLibraryId() {
    return libraryId;
  }

  UUID getPendingUserId() {
    return pendingUserId;
  }

  String getPendingAccountLabel() {
    return pendingAccountLabel;
  }

  Instant getPendingExpiresAt() {
    return pendingExpiresAt;
  }

  /** Whether the row is a pending grant of {@code userId} that has not expired at {@code now}. */
  boolean pendingFor(UUID userId, Instant now) {
    return userId.equals(pendingUserId)
        && pendingExpiresAt != null
        && pendingExpiresAt.isAfter(now);
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

  UUID getId() {
    return id;
  }

  String getAccessTokenCiphertext() {
    return accessTokenCiphertext;
  }

  Instant getAccessTokenExpiresAt() {
    return accessTokenExpiresAt;
  }

  /** When the access token was obtained: the row changes only with it or with the whole secret. */
  Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Whether the secret is past the end the provider named or the rejection set. */
  boolean expiredAt(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }

  /** The ciphertexts of an OAuth grant with its ends; the refresh token is the stored secret. */
  record Ciphered(String refresh, String access, Instant accessExpiresAt, Instant expiresAt) {

    @Override
    public String toString() {
      return "Ciphered[***]";
    }
  }

  @Override
  public String toString() {
    return "ConnectionToken[id=" + id + ", kind=" + kind + ", secret=***]";
  }
}
