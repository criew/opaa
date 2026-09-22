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
import io.opaa.group.GroupRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

  /** The stable {@code code} of the {@code 403} a scope below the Mindestgruppengröße produces. */
  public static final String SCOPE_NO_LONGER_USABLE = "IMPERSONATION_SCOPE_NOT_USABLE";

  private final DiagnosticImpersonationGrantRepository grantRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMembershipResolver membershipResolver;
  private final GroupSubjectDirectory groupDirectory;
  private final GroupSizeProperties groupSizeProperties;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  public DiagnosticImpersonationGrantService(
      DiagnosticImpersonationGrantRepository grantRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMembershipResolver membershipResolver,
      GroupSubjectDirectory groupDirectory,
      GroupSizeProperties groupSizeProperties,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.grantRepository = grantRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.membershipResolver = membershipResolver;
    this.groupDirectory = groupDirectory;
    this.groupSizeProperties = groupSizeProperties;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /**
   * Grants the befugnis. Only a {@code SYSTEM_ADMIN} may do so - granting a right and holding it
   * are separate acts, and this method is the granting one. Rejects a window that is empty, ends in
   * the past, or is longer than {@link DiagnosticImpersonationGrant#MAX_VALIDITY_MONTHS} months.
   *
   * <p><b>The scope is any provider group of the same organization</b> - {@link GroupKind#ORG_UNIT}
   * or {@link GroupKind#IDENTITY_PROVIDER} (ADR-0036, Entscheidung 3): a house that stays in token
   * mode never gets an {@code ORG_UNIT} group, and the befugnis would be ungrantable there for
   * good. An internal group is no Organisationseinheit and stays out, a group of a switched-off
   * provider reaches nobody, and a group below the Mindestgruppengröße is a person with a name
   * rather than a group - {@link #requireUsableScope} is asked here and again on every use.
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
    groupRepository
        .findById(creation.scopeGroupId())
        .filter(group -> organizationId.equals(group.getOrganizationId()))
        .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    requireUsableScope(creation.scopeGroupId(), organizationId, ValidationException::new);

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

  /**
   * Revokes what {@code issuerUserId}'s account still confers on others, as the deletion of that
   * account (ADR-0016, Nachtrag: the Auflage on a Kontolöschungsfunktion). Without this the
   * schema's cascade would take those rows from holders who remain - silently, and without the
   * revocation event every other end of a befugnis has. The event is the one {@link #revoke}
   * writes, so an evaluation reads both alike; the rows themselves then go with the cascade.
   *
   * <p>{@code MANDATORY}: the revocation is only true if the deletion it belongs to commits, so it
   * has to run in the caller's transaction, never in one of its own.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<DiagnosticImpersonationGrant> revokeGrantsIssuedBy(
      CurrentUser actor, UUID issuerUserId) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException("Nur die Administration darf die Befugnis entziehen");
    }
    Instant now = clock.instant();
    List<DiagnosticImpersonationGrant> affected =
        grantRepository.findUnspentIssuedBy(actor.organizationId(), issuerUserId, now);
    for (DiagnosticImpersonationGrant grant : affected) {
      grant.revoke(actor.id(), now);
      grantRepository.save(grant);
      recordGrantEvent(actor, grant, AuditEventType.DIAGNOSTIC_IMPERSONATION_REVOKED);
    }
    return affected;
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
   *
   * <p><b>The scope is measured again here</b> (ADR-0036, Entscheidung 3): a group of seven active
   * accounts at the time of granting can have one half a year later, and the befugnis would then be
   * a person context without the Schutzmechanik of one. The grant itself is left alone - it stays
   * valid and unrevoked, it is only not usable - so neither the protocol nor the administration's
   * overview gets a break out of a group that shrank.
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
    List<DiagnosticImpersonationGrant> forThisPerson =
        active.stream()
            .filter(candidate -> targetGroupIds.contains(candidate.getScopeGroupId()))
            .toList();
    if (forThisPerson.isEmpty()) {
      throw new AccessDeniedException(
          "Ihre Befugnis „Sicht als“ gilt nicht für die Organisationseinheit dieser Person.");
    }
    // A holder may legitimately hold several; the usable one decides, not the first one the query
    // happens to return - otherwise an unusable scope would hide a usable one, and the interface's
    // own answer (personContextAvailable) and this path would disagree.
    return forThisPerson.stream()
        .filter(
            candidate -> scopeRefusal(candidate.getScopeGroupId(), actor.organizationId()) == null)
        .findFirst()
        .orElseThrow(
            () ->
                new AccessDeniedException(
                    scopeRefusal(forThisPerson.get(0).getScopeGroupId(), actor.organizationId()),
                    SCOPE_NO_LONGER_USABLE));
  }

  /**
   * Whether {@code actor} holds a valid, unrevoked befugnis with a <b>usable</b> scope right now -
   * what a user interface needs in order to say whether the person context is selectable. The only
   * difference to {@link #requireImpersonationPermission} is the target person: a caller can hold a
   * usable befugnis and still be refused for somebody outside its scope.
   */
  @Transactional(readOnly = true)
  public boolean holdsImpersonationPermission(CurrentUser actor) {
    return impersonationAvailability(actor) == ImpersonationAvailability.USABLE;
  }

  /**
   * The three states an interface has to tell apart (#1879): no befugnis at all, one whose scope is
   * currently too small, or a usable one. Without the middle state the interface would say "Sie
   * halten keine" to somebody who holds one.
   */
  @Transactional(readOnly = true)
  public ImpersonationAvailability impersonationAvailability(CurrentUser actor) {
    List<DiagnosticImpersonationGrant> active =
        grantRepository.findActive(actor.organizationId(), actor.id(), clock.instant());
    if (active.isEmpty()) {
      return ImpersonationAvailability.NONE;
    }
    return active.stream()
            .anyMatch(
                grant -> scopeRefusal(grant.getScopeGroupId(), actor.organizationId()) == null)
        ? ImpersonationAvailability.USABLE
        : ImpersonationAvailability.SCOPE_TOO_SMALL;
  }

  /**
   * @see #impersonationAvailability
   */
  public enum ImpersonationAvailability {
    NONE,
    SCOPE_TOO_SMALL,
    USABLE
  }

  /**
   * Whether the scope is one right now, or why it is not: a provider group of this organization
   * that is effective - not dissolved, its provider switched on, not a token group its provider no
   * longer maintains (ADR-0036, Entscheidung 3: a frozen membership is no picture of the present) -
   * and reaching at least {@link GroupSizeProperties#minimumGroupSize()} active accounts. Asked at
   * the granting and at every use, so both answers come from one place and the refusal reads the
   * same in both.
   *
   * <p><b>The refusal never carries the size.</b> Below the Mindestgruppengröße a house withholds
   * the figure (ADR-0036, Entscheidung 9; {@code GroupSizeSignal}, {@code
   * GroupMemberDisclosureAdapter}), and "reaches one active account" about a named person's unit
   * would be the disclosure the mark exists to prevent.
   */
  private void requireUsableScope(
      UUID scopeGroupId, UUID organizationId, Function<String, RuntimeException> refusal) {
    String reason = scopeRefusal(scopeGroupId, organizationId);
    if (reason != null) {
      throw refusal.apply(reason);
    }
  }

  private String scopeRefusal(UUID scopeGroupId, UUID organizationId) {
    GroupSubject scope = groupDirectory.find(scopeGroupId).orElse(null);
    if (scope == null || !organizationId.equals(scope.organizationId())) {
      return "Der Geltungsbereich ist keine Gruppe dieser Organisation";
    }
    if (scope.internal()) {
      return "Der Geltungsbereich muss eine Anbietergruppe sein - eine interne Gruppe dieses"
          + " Hauses ist keine Organisationseinheit";
    }
    if (scope.dissolved()) {
      return "Der Geltungsbereich ist aufgelöst und kann keine neue Vollmacht mehr tragen";
    }
    if (scope.providerDisabled()) {
      return "Der Identitätsanbieter dieser Gruppe ist abgeschaltet; sie erreicht niemanden";
    }
    if (scope.unmaintained()) {
      return "Diese Token-Gruppe wird von ihrem Anbieter nicht mehr gepflegt; ihre Mitgliedschaft"
          + " ist eingefroren und bildet die Gegenwart nicht mehr ab";
    }
    if (membershipResolver.activeMemberCount(scopeGroupId, organizationId)
        < groupSizeProperties.minimumGroupSize()) {
      return "Der Geltungsbereich ist derzeit eine kleine Gruppe - er liegt unter der"
          + " Mindestgruppengröße, und ein Gruppenkontext dieser Größe gibt eine einzelne Person"
          + " preis";
    }
    return null;
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
