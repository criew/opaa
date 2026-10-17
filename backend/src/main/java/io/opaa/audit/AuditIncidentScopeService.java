package io.opaa.audit;

import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Request/approve lifecycle for the one personenbezogene exception, the anlassbezogene Klärung.
 * Deliberately separate from {@link AuditQueryService}: this class owns the Vier-Augen-Prinzip
 * workflow (request, approve, look up), the query service owns turning an approved grant into a
 * bounded {@code audit_log} read.
 */
@Service
public class AuditIncidentScopeService {

  private final AuditIncidentScopeGrantRepository repository;
  private final AuditPersonDirectory people;
  private final Clock clock;

  public AuditIncidentScopeService(
      AuditIncidentScopeGrantRepository repository, AuditPersonDirectory people, Clock clock) {
    this.repository = repository;
    this.people = people;
    this.clock = clock;
  }

  /**
   * Creates a new {@code PENDING} incident scope grant, not usable for querying until approved, and
   * counts the request in the current month.
   *
   * @throws NotFoundException if {@code subjectUserId} does not belong to {@code organizationId} -
   *     a grant must never be created, and no pseudonym ever minted at query time, against a person
   *     outside the requester's own organization.
   */
  @Transactional
  public AuditIncidentScopeGrant request(
      UUID organizationId,
      UUID requestedByUserId,
      UUID subjectUserId,
      Instant scopeStart,
      Instant scopeEnd,
      AuditIncidentScopePurpose purpose,
      String reason) {
    if (!people.belongsTo(organizationId, subjectUserId)) {
      throw new NotFoundException("Person nicht in dieser Organisation gefunden");
    }
    AuditIncidentScopeGrant grant =
        repository.save(
            new AuditIncidentScopeGrant(
                organizationId,
                subjectUserId,
                scopeStart,
                scopeEnd,
                purpose,
                reason,
                requestedByUserId));
    count(organizationId, Instant.now(clock), purpose, AuditIncidentScopeTransition.REQUESTED);
    return grant;
  }

  /**
   * Approves a pending grant, counting the approval in the current month and its end of usability
   * in the month of {@link AuditIncidentScopeGrant#getUsableUntil()}. Throws {@link
   * ValidationException} on Selbstfreigabe or a grant not pending. The row is locked, so of two
   * concurrent approvals exactly one succeeds and is counted; the other is rejected.
   */
  @Transactional
  public AuditIncidentScopeGrant approve(UUID organizationId, UUID scopeId, UUID approvedByUserId) {
    AuditIncidentScopeGrant grant =
        repository
            .findForApproval(scopeId, organizationId)
            .orElseThrow(() -> new NotFoundException("Vorgang nicht gefunden"));
    Instant now = Instant.now(clock);
    grant.approve(approvedByUserId, now);
    AuditIncidentScopeGrant approved = repository.save(grant);
    count(organizationId, now, grant.getPurpose(), AuditIncidentScopeTransition.APPROVED);
    count(
        organizationId,
        grant.getUsableUntil(),
        grant.getPurpose(),
        AuditIncidentScopeTransition.EXPIRED);
    return approved;
  }

  private void count(
      UUID organizationId,
      Instant at,
      AuditIncidentScopePurpose purpose,
      AuditIncidentScopeTransition transition) {
    repository.countTransition(
        organizationId,
        LocalDate.ofInstant(at, clock.getZone()).withDayOfMonth(1),
        purpose.name(),
        transition.name());
  }

  /**
   * Looks up a still-usable, approved grant, or throws 404/400 depending on why it cannot be used -
   * not yet approved, or past {@link AuditIncidentScopeGrant#getUsableUntil()}.
   */
  public AuditIncidentScopeGrant findApproved(UUID organizationId, UUID scopeId) {
    AuditIncidentScopeGrant grant = findOrThrow(organizationId, scopeId);
    if (!grant.isUsable(Instant.now(clock))) {
      String message =
          grant.isApproved()
              ? "Die Freigabe ist abgelaufen"
              : "Der Vorgang ist noch nicht freigegeben";
      throw new ValidationException(message);
    }
    return grant;
  }

  private AuditIncidentScopeGrant findOrThrow(UUID organizationId, UUID scopeId) {
    return repository
        .findByIdAndOrganizationId(scopeId, organizationId)
        .orElseThrow(() -> new NotFoundException("Vorgang nicht gefunden"));
  }
}
