package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Request/approve lifecycle for the one personenbezogene exception, the anlassbezogene Klärung.
 * Deliberately separate from {@link AuditQueryService}: this class owns the Vier-Augen-Prinzip
 * workflow (request, approve, look up), the query service owns turning an approved grant into a
 * bounded {@code audit_log} read. Request and approval each write their audit event in the same
 * transaction as the grant, so neither exists without the other.
 */
@Service
public class AuditIncidentScopeService {

  private final AuditIncidentScopeGrantRepository repository;
  private final AuditPersonDirectory people;
  private final AuditEventRecorder eventRecorder;
  private final AuditIncidentScopeProperties properties;
  private final Clock clock;

  public AuditIncidentScopeService(
      AuditIncidentScopeGrantRepository repository,
      AuditPersonDirectory people,
      AuditEventRecorder eventRecorder,
      AuditIncidentScopeProperties properties,
      Clock clock) {
    this.repository = repository;
    this.people = people;
    this.eventRecorder = eventRecorder;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Creates a new {@code PENDING} incident scope grant, not usable for querying until approved,
   * counts the request in the current month and records it as {@link
   * AuditEventType#INCIDENT_SCOPE_REQUESTED}. The time range must lie in the past and span at most
   * {@link AuditIncidentScopeProperties#maxDays()}: a Klärung looks back at a bounded incident, it
   * never watches a person live.
   *
   * @throws ValidationException if the time range ends in the future or is too long
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
    Instant now = Instant.now(clock);
    requireBoundedPast(scopeStart, scopeEnd, now);
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
    count(organizationId, now, purpose, AuditIncidentScopeTransition.REQUESTED);
    record(grant, requestedByUserId, AuditEventType.INCIDENT_SCOPE_REQUESTED, false);
    return grant;
  }

  /**
   * Approves a pending grant, counting the approval in the current month and its end of usability
   * in the month of {@link AuditIncidentScopeGrant#getUsableUntil()}, and records it as {@link
   * AuditEventType#INCIDENT_SCOPE_APPROVED}. The bounds of {@link #request} are checked again at
   * the moment of approval and against the bound in force then, so neither an older request nor a
   * lowered bound lets a grant through that could not be requested now. Throws {@link
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
    requireBoundedPast(grant.getScopeStart(), grant.getScopeEnd(), now);
    grant.approve(approvedByUserId, now);
    AuditIncidentScopeGrant approved = repository.save(grant);
    count(organizationId, now, grant.getPurpose(), AuditIncidentScopeTransition.APPROVED);
    count(
        organizationId,
        grant.getUsableUntil(),
        grant.getPurpose(),
        AuditIncidentScopeTransition.EXPIRED);
    record(approved, approvedByUserId, AuditEventType.INCIDENT_SCOPE_APPROVED, true);
    return approved;
  }

  /**
   * A time range that is incomplete or inverted is left to {@link AuditIncidentScopeGrant}'s own
   * check; only the bounds of this service are checked here.
   */
  private void requireBoundedPast(Instant scopeStart, Instant scopeEnd, Instant now) {
    if (scopeStart == null || scopeEnd == null || scopeStart.isAfter(scopeEnd)) {
      return;
    }
    if (scopeEnd.isAfter(now)) {
      throw new ValidationException(
          "Der Zeitraum einer Klärung darf nicht in der Zukunft enden: scopeEnd liegt nach jetzt");
    }
    int maxDays = properties.maxDays();
    if (Duration.between(scopeStart, scopeEnd).compareTo(Duration.ofDays(maxDays)) > 0) {
      throw new ValidationException(
          "Der Zeitraum einer Klärung umfasst höchstens " + maxDays + " Tage");
    }
  }

  /**
   * The audit event of a request or an approval. The object is the grant, the payload purpose and
   * time range; the named person stands only in the grant, so the event mints no pseudonym for her
   * and does not reveal a running Klärung in her self-disclosure.
   */
  private void record(
      AuditIncidentScopeGrant grant, UUID actorUserId, AuditEventType type, boolean approved) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("purpose", grant.getPurpose().name());
    after.put("scopeStart", grant.getScopeStart().toString());
    after.put("scopeEnd", grant.getScopeEnd().toString());
    if (approved) {
      after.put("usableUntil", grant.getUsableUntil().toString());
    }
    eventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(grant.getOrganizationId())
            .actor(actorUserId)
            .type(type)
            .object(AuditObjectType.AUDIT_LOG, grant.getId(), null)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
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
