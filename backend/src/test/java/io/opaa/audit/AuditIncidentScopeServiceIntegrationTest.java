package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.api.types.AuditIncidentScopeStatus;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one personenbezogene exception - anlassbezogene Klärung, Vier-Augen-Prinzip - against a real
 * Postgres database with the real, versioned Flyway schema applied, mirroring {@code
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
  @Autowired private Clock clock;

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
  // audit_log is partitioned by month with a fixed horizon around the moment the
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
    jdbcTemplate.update(
        "DELETE FROM audit_incident_scope_monthly_counts WHERE organization_id = ?",
        organizationId);
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
        .isInstanceOf(ValidationException.class);
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
        .isInstanceOf(ValidationException.class)
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

  /**
   * An owner change by someone else names the person only under {@code ownerId}; the approved grant
   * finds it there, but only for the event types that carry an owner.
   */
  @Test
  void approvedGrantAlsoReturnsOwnerEntriesNamingThePersonAsOwner() {
    String subjectRef = pseudonymService.pseudonymFor(subject, organizationId).toString();
    String otherRef = pseudonymService.pseudonymFor(approver, organizationId).toString();
    Instant at = scopeStart.plus(1, ChronoUnit.DAYS);
    AuditLogEntry handedOn =
        writeAuditEntry(
            requester,
            AuditEventType.ASSET_OWNER_CHANGED,
            "{\"ownerId\":\"" + subjectRef + "\"}",
            "{\"ownerId\":\"" + otherRef + "\"}",
            at);
    AuditLogEntry received =
        writeAuditEntry(
            requester,
            AuditEventType.SPACE_CREATED,
            null,
            "{\"name\":\"Team\",\"ownerId\":\"" + subjectRef + "\"}",
            at);
    // an event type without an owner never matches on its payload
    writeAuditEntry(
        requester,
        AuditEventType.LIBRARY_CHANGED,
        null,
        "{\"ownerId\":\"" + subjectRef + "\"}",
        at);
    // another person's owner change
    writeAuditEntry(
        requester,
        AuditEventType.ASSET_OWNER_CHANGED,
        "{\"ownerId\":\"" + otherRef + "\"}",
        "{\"ownerId\":\"" + UUID.randomUUID() + "\"}",
        at);
    // an older entry names the owner by account id
    AuditLogEntry formerly =
        writeAuditEntry(
            requester,
            AuditEventType.ASSET_OWNER_CHANGED,
            "{\"ownerId\":\"" + subject + "\"}",
            "{\"ownerId\":\"" + otherRef + "\"}",
            at);
    // a group as owner never matches the person
    writeAuditEntry(
        requester,
        AuditEventType.ASSET_OWNER_CHANGED,
        "{\"ownerId\":\"" + UUID.randomUUID() + "\"}",
        "{\"ownerId\":\"" + UUID.randomUUID() + "\"}",
        at);

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

    assertThat(result.getContent())
        .extracting(AuditLogEntry::getEventId)
        .containsExactlyInAnyOrder(
            handedOn.getEventId(), received.getEventId(), formerly.getEventId());
    assertThat(result.getTotalElements()).isEqualTo(3);
  }

  /**
   * A person who never got a pseudonym is still found by her account id in older owner entries; the
   * read mints no pseudonym.
   */
  @Test
  void approvedGrantFindsOlderOwnerEntriesOfAPersonWithoutPseudonym() {
    AuditLogEntry formerly =
        writeAuditEntry(
            requester,
            AuditEventType.SPACE_DELETED,
            "{\"name\":\"Team\",\"ownerId\":\"" + subject + "\"}",
            null,
            scopeStart.plus(1, ChronoUnit.DAYS));
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

    assertThat(result.getContent())
        .extracting(AuditLogEntry::getEventId)
        .containsExactly(formerly.getEventId());
    assertThat(pseudonymService.findExistingPseudonym(subject)).isEmpty();
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
        .isInstanceOf(ValidationException.class);

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
        .isInstanceOf(ValidationException.class);
  }

  /**
   * An approved grant must not remain usable indefinitely - once {@code usable_until} has passed,
   * {@code findApproved} must reject the lookup even though the grant's own status column still
   * says {@code APPROVED}.
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
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("abgelaufen");
  }

  /**
   * A grant must never be created against a person outside the requester's own organization - not
   * even PENDING, since a pseudonym for that person would otherwise be minted the first time anyone
   * ever queries the (never-approvable) grant.
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
          .isInstanceOf(NotFoundException.class);
    } finally {
      userRepository.deleteById(foreignUserId);
      organizationRepository.deleteById(foreignOrganizationId);
    }
  }

  /**
   * A read must never have the side effect of minting a pseudonym - a person who has never
   * triggered an audit event of their own has no pseudonym yet, and this GET must not be what gives
   * them one. An empty page (not an error) is the correct answer: the person genuinely has no
   * entries in the log.
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
  void everyRequestAndApprovalIsCountedInItsMonthAndTheEndOfUsabilityInItsOwn() {
    Instant before = Instant.now(clock);
    for (AuditIncidentScopePurpose purpose :
        List.of(
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            AuditIncidentScopePurpose.DATA_BREACH_INVESTIGATION)) {
      AuditIncidentScopeGrant grant =
          incidentScopeService.request(
              organizationId, requester, subject, scopeStart, scopeEnd, purpose, REASON);
      incidentScopeService.approve(organizationId, grant.getId(), approver);
    }
    // a request never approved counts as requested only
    incidentScopeService.request(
        organizationId,
        requester,
        subject,
        scopeStart,
        scopeEnd,
        AuditIncidentScopePurpose.EXTERNAL_AUDIT_OR_INSPECTION,
        REASON);
    Instant after = Instant.now(clock);

    assertThat(caseCounts("REQUESTED"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "SECURITY_INCIDENT", 2,
                "DATA_BREACH_INVESTIGATION", 1,
                "EXTERNAL_AUDIT_OR_INSPECTION", 1));
    assertThat(caseCounts("APPROVED"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("SECURITY_INCIDENT", 2, "DATA_BREACH_INVESTIGATION", 1));
    assertThat(caseCounts("EXPIRED"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("SECURITY_INCIDENT", 2, "DATA_BREACH_INVESTIGATION", 1));
    assertThat(caseMonths("REQUESTED")).isSubsetOf(month(before), month(after));
    assertThat(caseMonths("APPROVED")).isSubsetOf(month(before), month(after));
    assertThat(caseMonths("EXPIRED"))
        .isSubsetOf(
            month(before.plus(AuditIncidentScopeGrant.USABLE_WINDOW)),
            month(after.plus(AuditIncidentScopeGrant.USABLE_WINDOW)));
  }

  /**
   * Two approvals of the same grant at once, by two different persons, against the real row lock:
   * exactly one succeeds, the other is rejected as no longer pending, and the case is counted once.
   * Repeated over several grants, since a single pair may not overlap.
   */
  @Test
  void concurrentApprovalsOfOneGrantSucceedAndCountOnce() throws Exception {
    int grants = 5;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < grants; i++) {
        UUID grantId =
            incidentScopeService
                .request(
                    organizationId,
                    requester,
                    subject,
                    scopeStart,
                    scopeEnd,
                    AuditIncidentScopePurpose.SECURITY_INCIDENT,
                    REASON)
                .getId();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        for (UUID approvingPerson : List.of(approver, auditor)) {
          attempts.add(
              pool.submit(
                  () -> {
                    start.await();
                    try {
                      incidentScopeService.approve(organizationId, grantId, approvingPerson);
                      return true;
                    } catch (ValidationException alreadyApproved) {
                      return false;
                    }
                  }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> attempt : attempts) {
          succeeded += attempt.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        assertThat(succeeded).as("successful approvals of grant %s", grantId).isEqualTo(1);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(caseCounts("APPROVED"))
        .containsExactlyEntriesOf(Map.of("SECURITY_INCIDENT", grants));
    assertThat(caseCounts("EXPIRED")).containsExactlyEntriesOf(Map.of("SECURITY_INCIDENT", grants));
  }

  /**
   * In the schema a grant does not hold the accounts it names (the hold of an open or usable one is
   * the application's, in {@code LocalUserService#delete}): each of them can be deleted, and the
   * grant keeps the person as the pseudonym the protocol carries for them - one never seen in the
   * protocol gets a fresh one - so the Nachweis stays readable until its deletion period ends.
   */
  @Test
  void deletingTheAccountsAGrantNamesKeepsThemInItAsPseudonyms() {
    writeAuditEntryForActor(subject, scopeStart.plus(1, ChronoUnit.DAYS));
    UUID subjectPseudonym = pseudonymService.findExistingPseudonym(subject).orElseThrow();
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            REASON);
    incidentScopeService.approve(organizationId, grant.getId(), approver);

    userRepository.deleteById(subject);
    userRepository.deleteById(requester);
    userRepository.deleteById(approver);

    AuditIncidentScopeGrant kept = grantRepository.findById(grant.getId()).orElseThrow();
    assertThat(kept.getSubjectUserId()).isNull();
    assertThat(kept.getRequestedByUserId()).isNull();
    assertThat(kept.getApprovedByUserId()).isNull();
    assertThat(kept.getSubjectPseudonymId()).isEqualTo(subjectPseudonym);
    assertThat(kept.getRequestedByPseudonymId())
        .isNotNull()
        .isNotEqualTo(subjectPseudonym)
        .isNotEqualTo(kept.getApprovedByPseudonymId());
    assertThat(kept.getApprovedByPseudonymId()).isNotNull();
    assertThat(kept.getPurpose()).isEqualTo(AuditIncidentScopePurpose.SECURITY_INCIDENT);
    assertThat(kept.getReason()).isEqualTo(REASON);
    assertThat(kept.getApprovedAt()).isNotNull();
    // the mapping went with the account, and the grant's pseudonym is no way back into the log
    assertThat(pseudonymService.findExistingPseudonym(subject)).isEmpty();
    assertThat(
            queryService
                .byIncidentScope(
                    organizationId, auditor, REASON, grant.getId(), scopeStart, scopeEnd, 0, 50)
                .getContent())
        .isEmpty();
  }

  @Test
  void aRequestWhoseNamedPersonIsDeletedIsKeptButCannotBeApproved() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            requester,
            subject,
            scopeStart,
            scopeEnd,
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            REASON);

    userRepository.deleteById(subject);

    assertThat(grantRepository.findById(grant.getId()).orElseThrow().getSubjectPseudonymId())
        .isNotNull();
    assertThatThrownBy(() -> incidentScopeService.approve(organizationId, grant.getId(), approver))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("gelöscht");
    assertThat(grantRepository.findById(grant.getId()).orElseThrow().getStatus())
        .isEqualTo(AuditIncidentScopeStatus.PENDING);
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
        .isInstanceOf(ValidationException.class);
  }

  /** The counted cases of one transition by purpose, over every month. */
  private Map<String, Integer> caseCounts(String transition) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    jdbcTemplate.query(
        "SELECT purpose, sum(case_count) AS total FROM audit_incident_scope_monthly_counts"
            + " WHERE organization_id = ? AND transition = ? GROUP BY purpose",
        row -> {
          counts.put(row.getString("purpose"), row.getInt("total"));
        },
        organizationId,
        transition);
    return counts;
  }

  private List<LocalDate> caseMonths(String transition) {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT case_month FROM audit_incident_scope_monthly_counts"
            + " WHERE organization_id = ? AND transition = ?",
        LocalDate.class,
        organizationId,
        transition);
  }

  private LocalDate month(Instant instant) {
    return LocalDate.ofInstant(instant, clock.getZone()).withDayOfMonth(1);
  }

  private AuditLogEntry writeAuditEntry(
      UUID actorUserId, AuditEventType type, String before, String after, Instant recordedAt) {
    AuditLogEntry saved =
        auditLogService.record(
            AuditLogEntry.withoutSubject(
                organizationId,
                ActorKind.USER,
                pseudonymService.pseudonymFor(actorUserId, organizationId).toString(),
                type,
                AuditObjectType.SPACE,
                UUID.randomUUID().toString(),
                "Space",
                before,
                after,
                AuditOutcome.SUCCESS,
                null,
                null));
    jdbcTemplate.update(
        "UPDATE audit_log SET recorded_at = ? WHERE event_id = ?",
        Timestamp.from(recordedAt),
        saved.getEventId());
    return saved;
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
