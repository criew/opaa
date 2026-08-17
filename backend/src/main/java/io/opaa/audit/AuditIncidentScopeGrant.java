package io.opaa.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The one exception the specification names by name: the anlassbezogene Klärung of a security
 * incident (#393,
 * docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt). A grant
 * names person ({@link #subjectUserId}), time range ({@link #scopeStart}/{@link #scopeEnd}) and
 * purpose <em>in advance</em>, and only these three - not an open filter - become the technical
 * bound {@link AuditQueryService#byIncidentScope} enforces.
 *
 * <p>Vier-Augen-Prinzip: a grant is created {@code PENDING} by one AUDITOR ({@link
 * #requestedByUserId}) and stays unusable for querying until a <em>different</em> AUDITOR calls
 * {@link #approve}, which rejects a matching id (Selbstfreigabe) with an {@link
 * IllegalArgumentException}. There is no reject/revoke state on purpose - see {@link
 * AuditIncidentScopeStatus}'s Javadoc.
 */
@Entity
@Table(name = "audit_incident_scope_grants")
public class AuditIncidentScopeGrant {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "subject_user_id", nullable = false)
  private UUID subjectUserId;

  @Column(name = "scope_start", nullable = false)
  private Instant scopeStart;

  @Column(name = "scope_end", nullable = false)
  private Instant scopeEnd;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 40)
  private AuditIncidentScopePurpose purpose;

  @Column(name = "reason", nullable = false, length = 1000)
  private String reason;

  @Column(name = "requested_by_user_id", nullable = false)
  private UUID requestedByUserId;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "approved_by_user_id")
  private UUID approvedByUserId;

  @Column(name = "approved_at")
  private Instant approvedAt;

  /**
   * #393 code review, finding 7: an approved grant must not stay usable indefinitely - set at
   * approval time to {@link #approvedAt} plus {@link #USABLE_WINDOW}, checked by {@link #isUsable}.
   * Null while {@code PENDING}.
   */
  @Column(name = "usable_until")
  private Instant usableUntil;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 10)
  private AuditIncidentScopeStatus status;

  /**
   * How long an approved grant remains usable after approval (#393 code review, finding 7): 30 days
   * - long enough to carry out an anlassbezogene Klärung once approved, short enough that an
   * approved-but-forgotten grant does not become a standing personenbezogene view. Re-requesting
   * (with a fresh Vier-Augen-Prinzip approval) is the intended path past this window, not an
   * extension of the same grant.
   */
  static final Duration USABLE_WINDOW = Duration.ofDays(30);

  protected AuditIncidentScopeGrant() {}

  public AuditIncidentScopeGrant(
      UUID organizationId,
      UUID subjectUserId,
      Instant scopeStart,
      Instant scopeEnd,
      AuditIncidentScopePurpose purpose,
      String reason,
      UUID requestedByUserId) {
    if (scopeStart == null || scopeEnd == null || scopeStart.isAfter(scopeEnd)) {
      throw new IllegalArgumentException("scopeStart must not be after scopeEnd");
    }
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.subjectUserId = subjectUserId;
    this.scopeStart = scopeStart;
    this.scopeEnd = scopeEnd;
    this.purpose = purpose;
    this.reason = reason;
    this.requestedByUserId = requestedByUserId;
    this.status = AuditIncidentScopeStatus.PENDING;
  }

  @PrePersist
  void onCreate() {
    if (requestedAt == null) {
      requestedAt = Instant.now();
    }
  }

  /**
   * Approves this grant, making it usable for {@link AuditQueryService#byIncidentScope}.
   *
   * @throws IllegalArgumentException if {@code approvedByUserId} equals {@link #requestedByUserId}
   *     (Selbstfreigabe) or the grant is not {@code PENDING}
   */
  public void approve(UUID approvedByUserId) {
    if (status != AuditIncidentScopeStatus.PENDING) {
      throw new IllegalArgumentException("Incident scope is not pending approval");
    }
    if (approvedByUserId.equals(requestedByUserId)) {
      throw new IllegalArgumentException("Selbstfreigabe ist nicht zulässig");
    }
    this.approvedByUserId = approvedByUserId;
    this.approvedAt = Instant.now();
    this.usableUntil = approvedAt.plus(USABLE_WINDOW);
    this.status = AuditIncidentScopeStatus.APPROVED;
  }

  public boolean isApproved() {
    return status == AuditIncidentScopeStatus.APPROVED;
  }

  /**
   * True once approved and still within {@link #usableUntil} at {@code now} (#393 code review,
   * finding 7). A {@code PENDING} grant (where {@code usableUntil} is still null) is never usable.
   */
  public boolean isUsable(Instant now) {
    return status == AuditIncidentScopeStatus.APPROVED
        && usableUntil != null
        && !now.isAfter(usableUntil);
  }

  /** True if {@code [from, to]} lies entirely within this grant's approved scope. */
  public boolean covers(Instant from, Instant to) {
    return !from.isBefore(scopeStart) && !to.isAfter(scopeEnd);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getSubjectUserId() {
    return subjectUserId;
  }

  public Instant getScopeStart() {
    return scopeStart;
  }

  public Instant getScopeEnd() {
    return scopeEnd;
  }

  public AuditIncidentScopePurpose getPurpose() {
    return purpose;
  }

  public String getReason() {
    return reason;
  }

  public UUID getRequestedByUserId() {
    return requestedByUserId;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public UUID getApprovedByUserId() {
    return approvedByUserId;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public Instant getUsableUntil() {
    return usableUntil;
  }

  public AuditIncidentScopeStatus getStatus() {
    return status;
  }
}
