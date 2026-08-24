package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * #393: the one personenbezogene exception - anlassbezogene Klärung, Vier-Augen-Prinzip - against a
 * real Postgres database with the real, versioned Liquibase schema applied, mirroring {@code
 * AuditLogServiceIntegrationTest}.
 */
@OpaaIntegrationTest
class AuditIncidentScopeServiceIntegrationTest {

  @Autowired private AuditIncidentScopeService incidentScopeService;
  @Autowired private AuditQueryService queryService;
  @Autowired private AuditIncidentScopeGrantRepository grantRepository;
  @Autowired private AuditLogService auditLogService;
  @Autowired private AuditActorPseudonymService pseudonymService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final String REASON = "Verdacht auf unbefugten Zugriff auf Personalvorgaenge";

  private UUID organizationId;
  private UUID requester;
  private UUID approver;
  private UUID subject;
  // The querying AUDITOR - distinct from requester/approver, which #393's grant workflow keeps
  // as plain users of that workflow, not necessarily the same person who later reads via
  // AuditQueryService#byIncidentScope.
  private UUID auditor;
  private final List<UUID> createdUserIds = new ArrayList<>();
  // audit_log is partitioned by month (migration 017) with a fixed horizon around the moment the
  // migration ran - a hardcoded historical date can fall outside it and make the recorded_at
  // UPDATE in writeAuditEntryForActor fail with "no partition of relation found for row", so this
  // anchors to "now" instead.
  private final Instant scopeStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private final Instant scopeEnd = scopeStart.plus(27, ChronoUnit.DAYS);

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Incident Scope Test Org"))
            .getId();
    requester = createUser();
    approver = createUser();
    subject = createUser();
    auditor = createUser();
    userRepository
        .findById(auditor)
        .ifPresent(
            user -> {
              user.setSystemRole(SystemRole.AUDITOR);
              userRepository.save(user);
            });
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM audit_incident_scope_grants WHERE organization_id = ?", organizationId);
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    organizationRepository.deleteById(organizationId);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  @Test
  void aRequestedScopeIsPendingAndNotUsableForQuerying() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Verdacht auf unbefugten Zugriff auf Personalvorgaenge");

    assertThat(grant.getStatus()).isEqualTo(AuditIncidentScopeStatus.PENDING);
    assertThatThrownBy(
            () ->
                queryService.byIncidentScope(
                    organizationId, auditor, REASON, grant.getId(), scopeStart, scopeEnd, 0, 50))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void selfApprovalIsRejected() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Verdacht auf unbefugten Zugriff auf Personalvorgaenge");

    assertThatThrownBy(() -> incidentScopeService.approve(organizationId, grant.getId(), requester))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Selbstfreigabe");

    assertThat(grantRepository.findById(grant.getId()).orElseThrow().getStatus())
        .isEqualTo(AuditIncidentScopeStatus.PENDING);
  }

  @Test
  void anApprovalByADifferentPersonMakesTheGrantUsable() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Verdacht auf unbefugten Zugriff auf Personalvorgaenge");

    AuditIncidentScopeGrant approved =
        incidentScopeService.approve(organizationId, grant.getId(), approver);

    assertThat(approved.getStatus()).isEqualTo(AuditIncidentScopeStatus.APPROVED);
    assertThat(approved.getApprovedByUserId()).isEqualTo(approver);

    Page<AuditLogEntry> result =
        queryService.byIncidentScope(
            organizationId, auditor, REASON, grant.getId(), scopeStart, scopeEnd, 0, 50);
    assertThat(result).isNotNull();
  }

  @Test
  void approvedGrantReturnsOnlyEventsForItsNamedPersonWithinItsScope() {
    // the subject's own event, inside the approved window
    AuditLogEntry subjectEntry =
        writeAuditEntryForActor(subject, scopeStart.plus(1, ChronoUnit.DAYS));
    // a different person's event, same time window - must never surface through this path
    writeAuditEntryForActor(requester, scopeStart.plus(1, ChronoUnit.DAYS));

    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Verdacht auf unbefugten Zugriff auf Personalvorgaenge");
    incidentScopeService.approve(organizationId, grant.getId(), approver);

    Page<AuditLogEntry> result =
        queryService.byIncidentScope(
            organizationId, auditor, REASON, grant.getId(), scopeStart, scopeEnd, 0, 50);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getEventId()).isEqualTo(subjectEntry.getEventId());
  }

  @Test
  void aCallReachingBeyondTheApprovedScopeIsRejectedNotClamped() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Verdacht auf unbefugten Zugriff auf Personalvorgaenge");
    incidentScopeService.approve(organizationId, grant.getId(), approver);

    // requests a from earlier than the approved scope's start
    assertThatThrownBy(
            () ->
                queryService.byIncidentScope(
                    organizationId,
                    auditor,
                    REASON,
                    grant.getId(),
                    scopeStart.minus(1, ChronoUnit.DAYS),
                    scopeEnd,
                    0,
                    50))
        .isInstanceOf(IllegalArgumentException.class);

    // requests a to later than the approved scope's end
    assertThatThrownBy(
            () ->
                queryService.byIncidentScope(
                    organizationId,
                    auditor,
                    REASON,
                    grant.getId(),
                    scopeStart,
                    scopeEnd.plus(1, ChronoUnit.DAYS),
                    0,
                    50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * #393 code review, finding 7: an approved grant must not remain usable indefinitely - once
   * {@code usable_until} has passed, {@code findApproved} must reject the lookup even though the
   * grant's own status column still says {@code APPROVED}.
   */
  @Test
  void anApprovedGrantPastItsUsableUntilIsRejected() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Verdacht auf unbefugten Zugriff auf Personalvorgaenge");
    incidentScopeService.approve(organizationId, grant.getId(), approver);
    // Simulates the passage of time past AuditIncidentScopeGrant.USABLE_WINDOW without waiting
    // 30 real days - directly moves usable_until into the past on the already-approved row.
    jdbcTemplate.update(
        "UPDATE audit_incident_scope_grants SET usable_until = now() - interval '1 day'"
            + " WHERE id = ?",
        grant.getId());

    assertThatThrownBy(
            () ->
                queryService.byIncidentScope(
                    organizationId, auditor, REASON, grant.getId(), scopeStart, scopeEnd, 0, 50))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("abgelaufen");
  }

  /**
   * #393 code review, finding 8: a grant must never be created against a person outside the
   * requester's own organization - not even PENDING, since a pseudonym for that person would
   * otherwise be minted the first time anyone ever queries the (never-approvable) grant.
   */
  @Test
  void requestingAScopeAgainstAPersonFromAnotherOrganizationIsRejected() {
    UUID foreignOrganizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Other Org")).getId();
    User foreignUser =
        new User(UUID.randomUUID().toString(), "test-issuer", "foreign@example.com", "Foreign");
    foreignUser.setOrganizationId(foreignOrganizationId);
    UUID foreignUserId = userRepository.save(foreignUser).getId();

    try {
      assertThatThrownBy(
              () ->
                  incidentScopeService.request(
                      organizationId,
                      requester,
                      foreignUserId,
                      scopeStart,
                      scopeEnd,
                      AuditIncidentScopePurpose.SECURITY_INCIDENT,
                      "Verdacht auf unbefugten Zugriff auf Personalvorgaenge"))
          .isInstanceOf(ResponseStatusException.class);
    } finally {
      userRepository.deleteById(foreignUserId);
      organizationRepository.deleteById(foreignOrganizationId);
    }
  }

  /**
   * #393 code review, finding 8: a read must never have the side effect of minting a pseudonym - a
   * person who has never triggered an audit event of their own has no pseudonym yet, and this GET
   * must not be what gives them one. An empty page (not an error) is the correct answer: the person
   * genuinely has no entries in the log.
   */
  @Test
  void byIncidentScopeNeverMintsAPseudonymForAPersonWhoNeverTriggeredAnEvent() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Verdacht auf unbefugten Zugriff auf Personalvorgaenge");
    incidentScopeService.approve(organizationId, grant.getId(), approver);

    Page<AuditLogEntry> result =
        queryService.byIncidentScope(
            organizationId, auditor, REASON, grant.getId(), scopeStart, scopeEnd, 0, 50);

    assertThat(result.getContent()).isEmpty();
    assertThat(pseudonymService.findExistingPseudonym(subject)).isEmpty();
  }

  @Test
  void requestingScopeStartAfterScopeEndIsRejected() {
    assertThatThrownBy(
            () ->
                incidentScopeService.request(
                    organizationId,
                    requester,
                    subject,
                    scopeEnd,
                    scopeStart,
                    AuditIncidentScopePurpose.SECURITY_INCIDENT,
                    "ungueltiger Zeitraum"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private AuditLogEntry writeAuditEntryForActor(UUID actorUserId, Instant recordedAt) {
    // Uses the real AuditActorPseudonymService so the written actorRef matches exactly what
    // AuditQueryService#byIncidentScope resolves for the same user - the same pseudonym-minting
    // path AuditEventRecorder uses in production.
    String actorRef = pseudonymService.pseudonymFor(actorUserId, organizationId).toString();
    AuditLogEntry entry =
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.USER,
            actorRef,
            AuditEventType.LIBRARY_CREATED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            "lib-" + UUID.randomUUID(),
            "Bibliothek",
            null,
            null,
            AuditOutcome.SUCCESS,
            null,
            null);
    AuditLogEntry saved = auditLogService.record(entry);
    jdbcTemplate.update(
        "UPDATE audit_log SET recorded_at = ? WHERE event_id = ?",
        Timestamp.from(recordedAt),
        saved.getEventId());
    return saved;
  }
}
