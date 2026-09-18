package io.opaa.externalaccess.token;

import io.opaa.api.types.ExternalAccessTokenStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One personal access token (ADR-0035, Entscheidung 2). The raw value is never stored - only its
 * HMAC lookup hash and the prefix. The library selection is set once, at construction, and has no
 * mutator: a changed selection is a new token, because the audit entry of the issuance would
 * otherwise prove something that no longer holds.
 *
 * <p>{@code lastUsedOn} is a day, not a time, is advanced at most once per day and is cleared with
 * every revocation - including the one the daily run writes for an expiry. {@code lapseRecordedAt}
 * marks that the {@code API_TOKEN_EXPIRED} entry has been written, so it is written exactly once
 * and so the Loeschfrist of the row has a start.
 */
@Entity
@Table(name = "external_access_tokens")
public class ExternalAccessToken {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "name", nullable = false, length = 120, updatable = false)
  private String name;

  @Column(name = "token_prefix", nullable = false, length = 32, updatable = false)
  private String tokenPrefix;

  @Column(name = "token_lookup_hash", nullable = false, length = 64, updatable = false)
  private String tokenLookupHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "last_used_on")
  private LocalDate lastUsedOn;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "revocation_reason", length = 32)
  private ExternalAccessTokenRevocationReason revocationReason;

  @Column(name = "lapse_recorded_at")
  private Instant lapseRecordedAt;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "external_access_token_libraries",
      joinColumns = @JoinColumn(name = "token_id"))
  private Set<ExternalAccessTokenLibrary> libraries = new LinkedHashSet<>();

  protected ExternalAccessToken() {}

  public ExternalAccessToken(
      UUID userId,
      String name,
      String tokenPrefix,
      String tokenLookupHash,
      Instant createdAt,
      Instant expiresAt,
      Collection<UUID> libraryIds) {
    this.id = UUID.randomUUID();
    this.userId = Objects.requireNonNull(userId, "userId");
    this.name = Objects.requireNonNull(name, "name");
    this.tokenPrefix = Objects.requireNonNull(tokenPrefix, "tokenPrefix");
    this.tokenLookupHash = Objects.requireNonNull(tokenLookupHash, "tokenLookupHash");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(libraryIds, "libraryIds")
        .forEach(libraryId -> this.libraries.add(new ExternalAccessTokenLibrary(libraryId)));
    if (this.libraries.isEmpty()) {
      throw new IllegalArgumentException("an access token without a library selection is void");
    }
  }

  /** Neither revoked nor past its expiry - the two row-local conditions of the per-call check. */
  public boolean isActive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  /** The state both lists show; derived, never stored. */
  public ExternalAccessTokenStatus status(Instant now) {
    if (revocationReason != null) {
      return revocationReason.status();
    }
    return expiresAt.isAfter(now)
        ? ExternalAccessTokenStatus.ACTIVE
        : ExternalAccessTokenStatus.EXPIRED;
  }

  /**
   * Ends the token and drops "zuletzt benutzt" with it - the value only ever answered "may I revoke
   * this?". A second revocation keeps the first reason and moment.
   */
  public void revoke(ExternalAccessTokenRevocationReason reason, Instant now) {
    if (revokedAt != null) {
      return;
    }
    this.revocationReason = Objects.requireNonNull(reason, "reason");
    this.revokedAt = Objects.requireNonNull(now, "now");
    this.lastUsedOn = null;
  }

  /** Advances the day of last use; {@code false} when the row already carries {@code day}. */
  public boolean touch(LocalDate day) {
    if (day.equals(lastUsedOn)) {
      return false;
    }
    this.lastUsedOn = day;
    return true;
  }

  public void markLapseRecorded(Instant now) {
    this.lapseRecordedAt = now;
  }

  /**
   * Marks every selection entry outside {@code stillReleased} as gone for good; {@code true} when
   * at least one entry changed. See {@link ExternalAccessTokenLibrary} for why this is one-way.
   */
  boolean extinguishAllBut(Set<UUID> stillReleased, Instant now) {
    boolean changed = false;
    for (ExternalAccessTokenLibrary entry : libraries) {
      if (entry.isLive() && !stillReleased.contains(entry.getLibraryId())) {
        entry.extinguish(now);
        changed = true;
      }
    }
    return changed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getName() {
    return name;
  }

  public String getTokenPrefix() {
    return tokenPrefix;
  }

  public String getTokenLookupHash() {
    return tokenLookupHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public LocalDate getLastUsedOn() {
    return lastUsedOn;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public ExternalAccessTokenRevocationReason getRevocationReason() {
    return revocationReason;
  }

  public Instant getLapseRecordedAt() {
    return lapseRecordedAt;
  }

  /** Everything the person once selected - what the two lists show, not what acts today. */
  public Set<UUID> getSelectedLibraryIds() {
    return libraries.stream()
        .map(ExternalAccessTokenLibrary::getLibraryId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** The entries that have not been extinguished - the token's contribution to the intersection. */
  public Set<UUID> getLiveLibraryIds() {
    return libraries.stream()
        .filter(ExternalAccessTokenLibrary::isLive)
        .map(ExternalAccessTokenLibrary::getLibraryId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof ExternalAccessToken other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
