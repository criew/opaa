package io.opaa.audit;

import io.opaa.auth.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
  private final UserRepository userRepository;

  public AuditIncidentScopeService(
      AuditIncidentScopeGrantRepository repository, UserRepository userRepository) {
    this.repository = repository;
    this.userRepository = userRepository;
  }

  /**
   * Creates a new {@code PENDING} incident scope grant. Not usable for querying until approved.
   *
   * @throws ResponseStatusException 404 if {@code subjectUserId} does not belong to {@code
   *     organizationId} (#393 code review, finding 8) - a grant must never be created, and no
   *     pseudonym ever minted at query time, against a person outside the requester's own
   *     organization.
   */
  public AuditIncidentScopeGrant request(
      UUID organizationId,
      UUID requestedByUserId,
      UUID subjectUserId,
      Instant scopeStart,
      Instant scopeEnd,
      AuditIncidentScopePurpose purpose,
      String reason) {
    userRepository
        .findByIdAndOrganizationId(subjectUserId, organizationId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Person nicht in dieser Organisation gefunden"));
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
   * error, not "not found". {@code @Transactional} so two concurrent approval attempts against the
   * same grant serialise on this row instead of both succeeding with an arbitrary "last write wins"
   * {@code approved_by_user_id}.
   */
  @Transactional
  public AuditIncidentScopeGrant approve(UUID organizationId, UUID scopeId, UUID approvedByUserId) {
    AuditIncidentScopeGrant grant = findOrThrow(organizationId, scopeId);
    grant.approve(approvedByUserId);
    return repository.save(grant);
  }

  /**
   * Looks up a still-usable, approved grant, or throws 404/400 depending on why it cannot be used -
   * not yet approved, or past {@link AuditIncidentScopeGrant#getUsableUntil()} (#393 code review,
   * finding 7).
   */
  public AuditIncidentScopeGrant findApproved(UUID organizationId, UUID scopeId) {
    AuditIncidentScopeGrant grant = findOrThrow(organizationId, scopeId);
    if (!grant.isUsable(Instant.now())) {
      String message =
          grant.isApproved()
              ? "Die Freigabe ist abgelaufen"
              : "Der Vorgang ist noch nicht freigegeben";
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
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
