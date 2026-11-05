package io.opaa.externalaccess.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One interval of a library administration's consent to a named service account (ADR-0035,
 * Entscheidung 7). Historised like the Fremdzugangsfreigabe: a row is never deleted and never
 * reopened - a moved end closes it as {@link ServiceAccountConsentEndReason#REPLACED} and starts
 * the next one - so "could this Fachverfahren read this library in 2026?" stays answerable.
 *
 * <p>At most one row per pair is open ({@code ux_service_account_library_consents_open}); an open
 * row takes effect only while {@code expiresAt} lies ahead, read per call.
 */
@Entity
@Table(name = "service_account_library_consents")
public class ServiceAccountLibraryConsent {

  @Id private UUID id;

  @Column(name = "service_account_id", nullable = false, updatable = false)
  private UUID serviceAccountId;

  @Column(name = "library_id", nullable = false, updatable = false)
  private UUID libraryId;

  @Column(name = "granted_at", nullable = false, updatable = false)
  private Instant grantedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "end_reason", length = 20)
  private ServiceAccountConsentEndReason endReason;

  /** Claim columns, written only by their conditional updates in the repository. */
  @Column(name = "reminder_sent_at", insertable = false, updatable = false)
  private Instant reminderSentAt;

  @Column(name = "expiry_recorded_at", insertable = false, updatable = false)
  private Instant expiryRecordedAt;

  protected ServiceAccountLibraryConsent() {}

  public ServiceAccountLibraryConsent(
      UUID serviceAccountId, UUID libraryId, Instant grantedAt, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.serviceAccountId = Objects.requireNonNull(serviceAccountId, "serviceAccountId");
    this.libraryId = Objects.requireNonNull(libraryId, "libraryId");
    this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /** Open and not yet past its end - the one condition the view of a token reads. */
  public boolean isInForce(Instant now) {
    return endedAt == null && expiresAt.isAfter(now);
  }

  /**
   * Closes the row. A consent that already ran out closes at its own end as {@link
   * ServiceAccountConsentEndReason#EXPIRED}, whatever the occasion, so the history shows when it
   * really stopped taking effect.
   */
  public void end(ServiceAccountConsentEndReason reason, Instant now) {
    if (endedAt != null) {
      return;
    }
    if (!expiresAt.isAfter(now)) {
      this.endedAt = expiresAt;
      this.endReason = ServiceAccountConsentEndReason.EXPIRED;
      return;
    }
    this.endedAt = Objects.requireNonNull(now, "now");
    this.endReason = Objects.requireNonNull(reason, "reason");
  }

  public UUID getId() {
    return id;
  }

  public UUID getServiceAccountId() {
    return serviceAccountId;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public Instant getGrantedAt() {
    return grantedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public ServiceAccountConsentEndReason getEndReason() {
    return endReason;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof ServiceAccountLibraryConsent other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
