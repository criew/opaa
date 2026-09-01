package io.opaa.diagnosticaccess;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.GroupKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants, revokes and evaluates the "Sicht als" befugnis (docs/features/hybrid-retrieval.md,
 * Leitplanke (c)). Two properties this class exists to keep:
 *
 * <ul>
 *   <li>Holding {@code SYSTEM_ADMIN} confers nothing here. An administrator may <em>grant</em> the
 *       befugnis, but {@link #requireImpersonationPermission} looks only at rows of {@link
 *       DiagnosticImpersonationGrant} - there is no role branch in it, for any role.
 *   <li>The befugnis is unrelated to reading the protocol, which is {@code SystemRole.AUDITOR}'s
 *       and is checked in {@link DiagnosticContextLogQueryService}. Neither implies the other.
 * </ul>
 */
@Service
public class DiagnosticImpersonationGrantService {

  private final DiagnosticImpersonationGrantRepository grantRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMembershipResolver membershipResolver;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  public DiagnosticImpersonationGrantService(
      DiagnosticImpersonationGrantRepository grantRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMembershipResolver membershipResolver,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.grantRepository = grantRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.membershipResolver = membershipResolver;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /**
   * Grants the befugnis. Only a {@code SYSTEM_ADMIN} may do so - granting a right and holding it
   * are separate acts, and this method is the granting one. Rejects a window that is empty, ends in
   * the past, or is longer than {@link DiagnosticImpersonationGrant#MAX_VALIDITY_MONTHS} months;
   * rejects a scope that is not an {@link GroupKind#ORG_UNIT} group of the same organization.
   */
  @Transactional
  public DiagnosticImpersonationGrant grant(
      CurrentUser actor, DiagnosticImpersonationGrantCreation creation) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException("Nur die Administration darf die Befugnis vergeben");
    }
    Instant now = clock.instant();
    validateWindow(creation, now);

    UUID organizationId = actor.organizationId();
    userRepository
        .findByIdAndOrganizationId(creation.holderUserId(), organizationId)
        .orElseThrow(() -> new NotFoundException("Nutzer nicht gefunden"));
    Group scope =
        groupRepository
            .findById(creation.scopeGroupId())
            .filter(group -> organizationId.equals(group.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Organisationseinheit nicht gefunden"));
    if (scope.getKind() != GroupKind.ORG_UNIT) {
      throw new ValidationException(
          "Der Geltungsbereich muss eine Organisationseinheit sein, keine Ad-hoc-Gruppe");
    }

    DiagnosticImpersonationGrant saved =
        grantRepository.save(
            new DiagnosticImpersonationGrant(
                organizationId,
                creation.holderUserId(),
                creation.scopeGroupId(),
                creation.validFrom(),
                creation.validUntil(),
                actor.id(),
                now));
    recordGrantEvent(actor, saved, AuditEventType.DIAGNOSTIC_IMPERSONATION_GRANTED);
    return saved;
  }

  /** Revokes an existing grant early. Same restriction as {@link #grant}. */
  @Transactional
  public DiagnosticImpersonationGrant revoke(CurrentUser actor, UUID grantId) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException("Nur die Administration darf die Befugnis entziehen");
    }
    DiagnosticImpersonationGrant grant =
        grantRepository
            .findByIdAndOrganizationId(grantId, actor.organizationId())
            .orElseThrow(() -> new NotFoundException("Befugnis nicht gefunden"));
    grant.revoke(actor.id(), clock.instant());
    DiagnosticImpersonationGrant saved = grantRepository.save(grant);
    recordGrantEvent(actor, saved, AuditEventType.DIAGNOSTIC_IMPERSONATION_REVOKED);
    return saved;
  }

  /** All grants of the organization, newest first - the administration's own overview. */
  @Transactional(readOnly = true)
  public List<DiagnosticImpersonationGrant> list(CurrentUser actor) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException("Nur die Administration darf die Befugnisse einsehen");
    }
    return grantRepository.findByOrganizationIdOrderByGrantedAtDesc(actor.organizationId());
  }

  /**
   * The grant that lets {@code actor} assume {@code targetUserId}'s rights context right now, or an
   * {@link AccessDeniedException}. Requires an unrevoked, currently valid grant whose
   * Organisationseinheit the target person is a member of - a valid grant for a different unit is
   * no permission for this person.
   */
  @Transactional(readOnly = true)
  public DiagnosticImpersonationGrant requireImpersonationPermission(
      CurrentUser actor, UUID targetUserId) {
    Instant now = clock.instant();
    List<DiagnosticImpersonationGrant> active =
        grantRepository.findActive(actor.organizationId(), actor.id(), now);
    if (active.isEmpty()) {
      throw new AccessDeniedException(
          "Für „Sicht als“ ist eine eigene, befristete Befugnis nötig; Sie halten keine.");
    }
    java.util.Set<UUID> targetGroupIds = membershipResolver.groupIdsForUser(targetUserId);
    return active.stream()
        .filter(grant -> targetGroupIds.contains(grant.getScopeGroupId()))
        .findFirst()
        .orElseThrow(
            () ->
                new AccessDeniedException(
                    "Ihre Befugnis „Sicht als“ gilt nicht für die Organisationseinheit dieser"
                        + " Person."));
  }

  private void validateWindow(DiagnosticImpersonationGrantCreation creation, Instant now) {
    if (creation.validFrom() == null || creation.validUntil() == null) {
      throw new ValidationException("Die Befugnis braucht einen Beginn und ein Ende");
    }
    if (!creation.validUntil().isAfter(creation.validFrom())) {
      throw new ValidationException("Das Ende der Befugnis muss nach ihrem Beginn liegen");
    }
    if (!creation.validUntil().isAfter(now)) {
      throw new ValidationException("Die Befugnis endet bereits in der Vergangenheit");
    }
    Instant latestAllowed =
        creation
            .validFrom()
            .atZone(ZoneOffset.UTC)
            .plusMonths(DiagnosticImpersonationGrant.MAX_VALIDITY_MONTHS)
            .toInstant();
    if (creation.validUntil().isAfter(latestAllowed)) {
      throw new ValidationException(
          "Die Befugnis darf höchstens "
              + DiagnosticImpersonationGrant.MAX_VALIDITY_MONTHS
              + " Monate gelten");
    }
  }

  /**
   * Object is the holder's pseudonym under {@link AuditObjectType#USER_ACCOUNT}, mirroring how
   * {@code UserService} records a role change - the befugnis is a privilege of an account, and an
   * auditor looks for it there.
   */
  private void recordGrantEvent(
      CurrentUser actor, DiagnosticImpersonationGrant grant, AuditEventType eventType) {
    UUID pseudonym =
        auditEventRecorder.pseudonymFor(grant.getHolderUserId(), grant.getOrganizationId());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(grant.getOrganizationId())
            .actor(actor.id())
            .type(eventType)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, grant.getHolderUserId())
            .after(
                Map.of(
                    "scopeGroupId", grant.getScopeGroupId().toString(),
                    "validFrom", grant.getValidFrom().toString(),
                    "validUntil", grant.getValidUntil().toString(),
                    "revoked", String.valueOf(grant.getRevokedAt() != null)))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
