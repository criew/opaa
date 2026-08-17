package io.opaa.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Request/approve lifecycle for the one personenbezogene exception, the anlassbezogene Klärung
 * (#393, decision #355). Deliberately separate from {@link AuditQueryService}: this class owns the
 * Vier-Augen-Prinzip workflow (request, approve, look up), the query service owns turning an
 * approved grant into a bounded {@code audit_log} read.
 */
@Service
public class AuditIncidentScopeService {

  private final AuditIncidentScopeGrantRepository repository;

  public AuditIncidentScopeService(AuditIncidentScopeGrantRepository repository) {
    this.repository = repository;
  }

  /** Creates a new {@code PENDING} incident scope grant. Not usable for querying until approved. */
  public AuditIncidentScopeGrant request(
      UUID organizationId,
      UUID requestedByUserId,
      UUID subjectUserId,
      Instant scopeStart,
      Instant scopeEnd,
      AuditIncidentScopePurpose purpose,
      String reason) {
    return repository.save(
        new AuditIncidentScopeGrant(
            organizationId,
            subjectUserId,
            scopeStart,
            scopeEnd,
            purpose,
            reason,
            requestedByUserId));
  }

  /**
   * Approves a pending grant. Throws {@link IllegalArgumentException} (mapped to a 400 by {@code
   * GlobalExceptionHandler}) on Selbstfreigabe or a grant that is not pending - both are caller
   * error, not "not found".
   */
  public AuditIncidentScopeGrant approve(UUID organizationId, UUID scopeId, UUID approvedByUserId) {
    AuditIncidentScopeGrant grant = findOrThrow(organizationId, scopeId);
    grant.approve(approvedByUserId);
    return repository.save(grant);
  }

  /** Looks up an approved grant, or throws 404/400 depending on why it cannot be used. */
  public AuditIncidentScopeGrant findApproved(UUID organizationId, UUID scopeId) {
    AuditIncidentScopeGrant grant = findOrThrow(organizationId, scopeId);
    if (!grant.isApproved()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Der Vorgang ist noch nicht freigegeben");
    }
    return grant;
  }

  private AuditIncidentScopeGrant findOrThrow(UUID organizationId, UUID scopeId) {
    return repository
        .findByIdAndOrganizationId(scopeId, organizationId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vorgang nicht gefunden"));
  }
}
