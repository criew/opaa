package io.opaa.diagnosticaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One grant of the "Sicht als" befugnis (docs/features/hybrid-retrieval.md, Leitplanke (c)): always
 * bound to exactly one holder, one Organisationseinheit ({@code scopeGroupId}, a group of kind
 * {@code ORG_UNIT}) and one validity window. Both are mandatory columns, so an unbounded, scopeless
 * permanent right cannot exist as a row - see {@code chk_diagnostic_impersonation_grants_validity},
 * which additionally caps the window at twelve months.
 *
 * <p>Deliberately not a value on {@code users.system_role}: the befugnis must be grantable on its
 * own and must never be implied by {@code SYSTEM_ADMIN}.
 */
@Entity
@Table(name = "diagnostic_impersonation_grants")
public class DiagnosticImpersonationGrant {

  /** The longest window a single grant may cover; mirrored by the database check constraint. */
  public static final int MAX_VALIDITY_MONTHS = 12;

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "holder_user_id", nullable = false)
  private UUID holderUserId;

  @Column(name = "scope_group_id", nullable = false)
  private UUID scopeGroupId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_until", nullable = false)
  private Instant validUntil;

  @Column(name = "granted_by_user_id", nullable = false)
  private UUID grantedByUserId;

  @Column(name = "granted_at", nullable = false)
  private Instant grantedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_by_user_id")
  private UUID revokedByUserId;

  protected DiagnosticImpersonationGrant() {}

  public DiagnosticImpersonationGrant(
      UUID organizationId,
      UUID holderUserId,
      UUID scopeGroupId,
      Instant validFrom,
      Instant validUntil,
      UUID grantedByUserId,
      Instant grantedAt) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.holderUserId = holderUserId;
    this.scopeGroupId = scopeGroupId;
    this.validFrom = validFrom;
    this.validUntil = validUntil;
    this.grantedByUserId = grantedByUserId;
    this.grantedAt = grantedAt;
  }

  /** Whether this grant confers anything at {@code at} - not revoked and inside its window. */
  public boolean isActiveAt(Instant at) {
    return revokedAt == null && !at.isBefore(validFrom) && at.isBefore(validUntil);
  }

  /** Idempotent: a second revocation keeps the first one's timestamp and actor. */
  public void revoke(UUID actorUserId, Instant at) {
    if (revokedAt == null) {
      this.revokedAt = at;
      this.revokedByUserId = actorUserId;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getHolderUserId() {
    return holderUserId;
  }

  public UUID getScopeGroupId() {
    return scopeGroupId;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getValidUntil() {
    return validUntil;
  }

  public UUID getGrantedByUserId() {
    return grantedByUserId;
  }

  public Instant getGrantedAt() {
    return grantedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public UUID getRevokedByUserId() {
    return revokedByUserId;
  }
}
