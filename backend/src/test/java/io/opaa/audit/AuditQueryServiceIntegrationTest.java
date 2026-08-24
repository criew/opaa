package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #393: the four revision access paths against a real Postgres database with the real, versioned
 * Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}),
 * mirroring {@code AuditLogServiceIntegrationTest}.
 *
 * <p>{@link #noAccessPathAcceptsOrSortsByActor()} is the acceptance criterion's dedicated,
 * cross-cutting test: it exercises every public method {@link AuditQueryService} declares (not a
 * hardcoded name list - #393 code review, nit 4: a hardcoded list would silently stop covering a
 * newly added method) and proves none of them exposes an actor/person entry point, either by
 * parameter type ({@code Sort}/{@code Pageable}) or by parameter name (real names, not {@code
 * arg0}/{@code arg1} - see the {@code -parameters} compiler flag {@code build.gradle.kts} now sets
 * for exactly this). Matches {@code io.opaa.api.AuditControllerTest}'s {@code
 * noEndpointAcceptsAnActorOrSortRequestParameter}/{@code
 * noParameterIsUnannotatedOrClientControlledSort} for the same claim at the HTTP layer.
 *
 * <p>#394: {@link #everySuccessfulAccessWritesItsOwnSelfLogEntry()} through {@link
 * #queryingTheSelfLogEntriesThemselvesCreatesAnotherOne()} prove the self-logging funnel this class
 * now wraps every access in - a successful query, a denied one (wrong role, missing reason,
 * business-rule rejection), that the denied entry survives the very exception that triggered it
 * (the transactional claim {@link AuditQueryService}'s own Javadoc makes), and that reading the
 * self-log entries back is itself logged again.
 */
@OpaaIntegrationTest
class AuditQueryServiceIntegrationTest {

  private static final String REASON = "Quartalsrevision Q1 2026";

  @Autowired private AuditQueryService queryService;
  @Autowired private AuditLogService auditLogService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private AuditIncidentScopeService incidentScopeService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID organizationId;
  private UUID auditorId;
  private UUID regularUserId;
  // audit_log is partitioned by month (migration 017) with a fixed horizon around the moment the
  // migration ran - a hardcoded historical date can fall outside it and make the recorded_at
  // UPDATE below fail with "no partition of relation found for row", so this anchors to "now"
  // instead.
  private final Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Audit Query Test Org"))
            .getId();
    auditorId = createUser(SystemRole.AUDITOR);
    regularUserId = createUser(SystemRole.USER);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteById(auditorId);
    userRepository.deleteById(regularUserId);
    organizationRepository.deleteById(organizationId);
  }

  private UUID createUser(SystemRole role) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    return userRepository.save(user).getId();
  }

  private AuditLogEntry writeEntry(
      String objectId, AuditEventType eventType, String correlationRef, Instant recordedAt) {
    AuditLogEntry entry =
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.USER,
            "pseud-actor-" + objectId,
            eventType,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            objectId,
            "Bibliothek " + objectId,
            null,
            null,
            AuditOutcome.SUCCESS,
            null,
            correlationRef);
    AuditLogEntry saved = auditLogService.record(entry);
    // recorded_at has no DB DEFAULT and is set by @PrePersist to Instant.now() - override it
    // directly so this test can control ordering/time-range boundaries deterministically.
    jdbcTemplate.update(
        "UPDATE audit_log SET recorded_at = ? WHERE event_id = ?",
        Timestamp.from(recordedAt),
        saved.getEventId());
    return saved;
  }

  @Test
  void byObjectReturnsOnlyEntriesForThatObjectWithinTheTimeRange() {
    writeEntry("lib-1", AuditEventType.LIBRARY_CREATED, null, base);
    writeEntry("lib-1", AuditEventType.LIBRARY_CHANGED, null, base.plus(1, ChronoUnit.DAYS));
    writeEntry("lib-2", AuditEventType.LIBRARY_CREATED, null, base);
    // outside the requested time range
    writeEntry("lib-1", AuditEventType.LIBRARY_DELETED, null, base.minus(10, ChronoUnit.DAYS));

    Page<AuditLogEntry> result =
        queryService.byObject(
            organizationId,
            auditorId,
            REASON,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            "lib-1",
            base.minus(1, ChronoUnit.HOURS),
            base.plus(2, ChronoUnit.DAYS),
            0,
            50);

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent()).allSatisfy(e -> assertThat(e.getObjectId()).isEqualTo("lib-1"));
  }

  @Test
  void byTimeRangeIgnoresObjectAndReturnsEverythingInWindow() {
    writeEntry("lib-1", AuditEventType.LIBRARY_CREATED, null, base);
    writeEntry("lib-2", AuditEventType.SPACE_CREATED, null, base.plus(1, ChronoUnit.HOURS));

    Page<AuditLogEntry> result =
        queryService.byTimeRange(
            organizationId,
            auditorId,
            REASON,
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.DAYS),
            0,
            50);

    assertThat(result.getContent()).hasSize(2);
  }

  @Test
  void byEventTypeFiltersOnEventTypeOnly() {
    writeEntry("lib-1", AuditEventType.LIBRARY_CREATED, null, base);
    writeEntry("lib-2", AuditEventType.LIBRARY_CREATED, null, base);
    writeEntry("lib-3", AuditEventType.LIBRARY_DELETED, null, base);

    Page<AuditLogEntry> result =
        queryService.byEventType(
            organizationId,
            auditorId,
            REASON,
            AuditEventType.LIBRARY_CREATED,
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.HOURS),
            0,
            50);

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent())
        .allSatisfy(e -> assertThat(e.getEventType()).isEqualTo(AuditEventType.LIBRARY_CREATED));
  }

  @Test
  void byCorrelationReturnsOnlyEntriesSharingTheSameVorgang() {
    writeEntry("lib-1", AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED, "sync-2026-02-16", base);
    writeEntry("lib-2", AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED, "sync-2026-02-16", base);
    writeEntry("lib-3", AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED, "sync-other-run", base);

    Page<AuditLogEntry> result =
        queryService.byCorrelation(
            organizationId,
            auditorId,
            REASON,
            "sync-2026-02-16",
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.HOURS),
            0,
            50);

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent())
        .allSatisfy(e -> assertThat(e.getCorrelationRef()).isEqualTo("sync-2026-02-16"));
  }

  /**
   * #393 code review, finding 2: {@code by-object} must reject {@code objectType=USER_ACCOUNT}
   * outright - a {@code USER_ACCOUNT} object's {@code object_id} is the same pseudonym {@code
   * actorRef} carries on that same person's own actions, so accepting it here would reconstruct
   * exactly the excluded "alle Ereignisse, bei denen Person X betroffen war" view via {@code
   * by-time-range} (read a pseudonym off {@code actorRef}) followed by this path (feed it back in
   * as {@code objectId}).
   */
  @Test
  void byObjectRejectsUserAccountObjectType() {
    assertThatThrownBy(
            () ->
                queryService.byObject(
                    organizationId,
                    auditorId,
                    REASON,
                    AuditObjectType.USER_ACCOUNT,
                    "pseud-some-person",
                    base.minus(1, ChronoUnit.HOURS),
                    base.plus(1, ChronoUnit.HOURS),
                    0,
                    50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("USER_ACCOUNT");
  }

  @Test
  void everyOtherObjectTypeIsStillAcceptedByObject() {
    writeEntry("lib-1", AuditEventType.LIBRARY_CREATED, null, base);

    Page<AuditLogEntry> result =
        queryService.byObject(
            organizationId,
            auditorId,
            REASON,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            "lib-1",
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.HOURS),
            0,
            50);

    assertThat(result.getContent()).hasSize(1);
  }

  /**
   * #393 code review, finding 3: a time range wider than {@link
   * AuditQueryService#MAX_TIME_RANGE_DAYS} is a disguised full extract, not a bounded revision
   * query, and must be rejected rather than silently served.
   */
  @Test
  void aTimeRangeWiderThanTheMaximumIsRejected() {
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId,
                    auditorId,
                    REASON,
                    base,
                    base.plus(AuditQueryService.MAX_TIME_RANGE_DAYS + 1, ChronoUnit.DAYS),
                    0,
                    50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aTimeRangeExactlyAtTheMaximumIsAccepted() {
    Page<AuditLogEntry> result =
        queryService.byTimeRange(
            organizationId,
            auditorId,
            REASON,
            base,
            base.plus(AuditQueryService.MAX_TIME_RANGE_DAYS, ChronoUnit.DAYS),
            0,
            50);

    assertThat(result).isNotNull();
  }

  /**
   * #393 code review, finding 3, second half: {@link AuditQueryService#MAX_PAGE_SIZE} alone only
   * bounds a single page - without a bound on how many pages a query can page through, {@code
   * page=0..n} against a wide time range turns "bounded per page" back into an effectively
   * unbounded full extract in slices. #393 re-review, nit 3: rejected with 400, not silently
   * clamped to the last usable page - the same "abgewiesen, nicht gekappt" principle {@link
   * AuditQueryService#byIncidentScope} already applies to a time range reaching outside its grant,
   * applied here to page depth too.
   */
  @Test
  void aPageIndexBeyondTheMaximumIsRejectedNotClamped() {
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId,
                    auditorId,
                    REASON,
                    base.minus(1, ChronoUnit.HOURS),
                    base.plus(1, ChronoUnit.HOURS),
                    AuditQueryService.MAX_PAGE_INDEX + 1,
                    50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPageIndexExactlyAtTheMaximumIsAccepted() {
    Page<AuditLogEntry> result =
        queryService.byTimeRange(
            organizationId,
            auditorId,
            REASON,
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.HOURS),
            AuditQueryService.MAX_PAGE_INDEX,
            50);

    assertThat(result.getNumber()).isEqualTo(AuditQueryService.MAX_PAGE_INDEX);
  }

  @Test
  void aNegativePageIndexIsRejected() {
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId,
                    auditorId,
                    REASON,
                    base.minus(1, ChronoUnit.HOURS),
                    base.plus(1, ChronoUnit.HOURS),
                    -1,
                    50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aQueryWithoutAMandatoryTimeRangeIsRejected() {
    assertThatThrownBy(
            () -> queryService.byTimeRange(organizationId, auditorId, REASON, null, base, 0, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> queryService.byTimeRange(organizationId, auditorId, REASON, base, null, 0, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId, auditorId, REASON, base.plus(1, ChronoUnit.DAYS), base, 0, 50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theResultSetIsBoundedRegardlessOfTheRequestedPageSize() {
    for (int i = 0; i < 12; i++) {
      writeEntry("lib-" + i, AuditEventType.LIBRARY_CREATED, null, base);
    }

    Page<AuditLogEntry> result =
        queryService.byTimeRange(
            organizationId,
            auditorId,
            REASON,
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.HOURS),
            0,
            // requests far more than AuditQueryService.MAX_PAGE_SIZE
            AuditQueryService.MAX_PAGE_SIZE + 5000);

    assertThat(result.getSize()).isEqualTo(AuditQueryService.MAX_PAGE_SIZE);
  }

  @Test
  void resultsAreOrderedByRecordedAtNeverByActor() {
    // actorRef is deliberately in descending alphabetical order of objectId while recordedAt is
    // ascending, so an actor-based sort and a recordedAt-based sort would disagree on the order.
    writeEntry("a-first", AuditEventType.LIBRARY_CREATED, null, base);
    writeEntry("z-second", AuditEventType.LIBRARY_CREATED, null, base.plus(1, ChronoUnit.HOURS));
    writeEntry("m-third", AuditEventType.LIBRARY_CREATED, null, base.plus(2, ChronoUnit.HOURS));

    Page<AuditLogEntry> result =
        queryService.byTimeRange(
            organizationId,
            auditorId,
            REASON,
            base.minus(1, ChronoUnit.HOURS),
            base.plus(3, ChronoUnit.HOURS),
            0,
            50);

    assertThat(result.getContent().stream().map(AuditLogEntry::getObjectId).toList())
        .containsExactly("a-first", "z-second", "m-third");
  }

  /**
   * The acceptance criterion's cross-cutting test: every access path {@link AuditQueryService}
   * exposes takes no actor/person parameter and cannot be asked to sort or group by one - proven
   * structurally (the method signatures below simply do not accept such a parameter) rather than by
   * trying every possible malicious input, which a closed API surface makes unnecessary.
   *
   * <p>#394: {@code callerId} is deliberately excluded from the forbidden list - see {@link
   * AuditQueryService}'s own Javadoc for why identifying who is asking (for self-logging) is not
   * the same thing as accepting a person filter.
   */
  @Test
  void noAccessPathAcceptsOrSortsByActor() {
    // "objectId" and "scopeId" are legitimate technical identifiers, not person filters -
    // excluded here the same way AuditControllerTest excludes "scopeId"; everything else on this
    // forbidden list would let a caller name, filter or sort by the acting person.
    List<String> forbiddenSubstrings = List.of("actor", "sort", "person", "subject");
    List<Method> publicMethods =
        Arrays.stream(AuditQueryService.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(m -> !m.isSynthetic())
            .toList();

    // A regression that removed every public method would make the loop below vacuously pass -
    // guard against that the same way AuditControllerTest guards its own method count.
    assertThat(publicMethods)
        .as("AuditQueryService must still declare its five #393 access paths")
        .hasSize(5);

    for (Method method : publicMethods) {
      for (Parameter parameter : method.getParameters()) {
        Class<?> type = parameter.getType();
        assertThat(type)
            .as(
                "%s must not accept a caller-supplied Sort/Pageable (parameter %s)",
                method.getName(), parameter.getName())
            .isNotIn(Sort.class, Pageable.class);

        String lowerName = parameter.getName().toLowerCase(Locale.ROOT);
        boolean forbidden = forbiddenSubstrings.stream().anyMatch(lowerName::contains);
        assertThat(forbidden)
            .as(
                "%s has a parameter named \"%s\" - actor/person must never be an input to a"
                    + " revision access path (#393)",
                method.getName(), parameter.getName())
            .isFalse();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // #394: the access on audit_log itself creates its own entry, including for a denied attempt.
  // ---------------------------------------------------------------------------------------------

  @Test
  void everySuccessfulAccessWritesItsOwnSelfLogEntry() {
    queryService.byTimeRange(
        organizationId,
        auditorId,
        REASON,
        base.minus(1, ChronoUnit.HOURS),
        base.plus(1, ChronoUnit.HOURS),
        0,
        50);

    List<AuditLogEntry> selfLogEntries = findAuditLogAccessedEntries();
    assertThat(selfLogEntries).hasSize(1);
    AuditLogEntry entry = selfLogEntries.get(0);
    assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(entry.getReason()).isEqualTo(REASON);
    assertThat(entry.getObjectType()).isEqualTo(AuditObjectType.AUDIT_LOG);
    assertThat(entry.getAfter()).contains("by-time-range");
  }

  /**
   * A non-AUDITOR caller is rejected with {@link AccessDeniedException} - enforced inside {@link
   * AuditQueryService} itself, not only {@code @PreAuthorize} on the controller, precisely so this
   * denial can be logged (see the class Javadoc for why an annotation-only check would make this
   * invisible here).
   */
  @Test
  void aNonAuditorCallerIsDeniedAndTheDenialIsLogged() {
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId,
                    regularUserId,
                    REASON,
                    base.minus(1, ChronoUnit.HOURS),
                    base.plus(1, ChronoUnit.HOURS),
                    0,
                    50))
        .isInstanceOf(AccessDeniedException.class);

    List<AuditLogEntry> selfLogEntries = findAuditLogAccessedEntries();
    assertThat(selfLogEntries).hasSize(1);
    assertThat(selfLogEntries.get(0).getOutcome()).isEqualTo(AuditOutcome.DENIED);
  }

  /**
   * "Der Anlass ist bei diesen Einträgen ein Pflichtfeld; eine Abfrage ohne Anlass wird abgewiesen"
   * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt) - and
   * the rejection is itself logged, exactly like every other denied attempt.
   */
  @Test
  void aMissingReasonIsRejectedAndTheRejectionIsLogged() {
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId,
                    auditorId,
                    "   ",
                    base.minus(1, ChronoUnit.HOURS),
                    base.plus(1, ChronoUnit.HOURS),
                    0,
                    50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pflichtfeld");

    List<AuditLogEntry> selfLogEntries = findAuditLogAccessedEntries();
    assertThat(selfLogEntries).hasSize(1);
    assertThat(selfLogEntries.get(0).getOutcome()).isEqualTo(AuditOutcome.DENIED);
  }

  /**
   * A business-rule rejection (here: the mandatory time range) is logged exactly like every other
   * denied attempt - not just the role/reason checks.
   */
  @Test
  void aRejectedTimeRangeIsAlsoLogged() {
    assertThatThrownBy(
            () -> queryService.byTimeRange(organizationId, auditorId, REASON, null, base, 0, 50))
        .isInstanceOf(IllegalArgumentException.class);

    List<AuditLogEntry> selfLogEntries = findAuditLogAccessedEntries();
    assertThat(selfLogEntries).hasSize(1);
    assertThat(selfLogEntries.get(0).getOutcome()).isEqualTo(AuditOutcome.DENIED);
  }

  /**
   * The transactional claim {@link AuditQueryService}'s own Javadoc makes: the self-log entry for a
   * rejected attempt survives the rejection - proven here the same way {@code
   * AuditLogServiceIntegrationTest} proves the sibling claim for an ordinary event, against a real
   * transaction manager and real Postgres rather than a mocked one. There is no ambient transaction
   * around either the failing call above or this test method itself, so the assertion below is
   * really checking that the write already committed on its own, not merely that it survived a
   * rollback this test triggered.
   */
  @Test
  void theDeniedEntrySurvivesTheRejectionThatTriggeredIt() {
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId,
                    regularUserId,
                    REASON,
                    base,
                    base.plus(1, ChronoUnit.HOURS),
                    0,
                    50))
        .isInstanceOf(AccessDeniedException.class);

    // A fresh read, not the same in-memory reference the failing call above might have held.
    List<AuditLogEntry> selfLogEntries = findAuditLogAccessedEntries();
    assertThat(selfLogEntries).hasSize(1);
    assertThat(selfLogEntries.get(0).getOutcome()).isEqualTo(AuditOutcome.DENIED);
  }

  /**
   * PR #450 review, finding 5: {@link #theDeniedEntrySurvivesTheRejectionThatTriggeredIt} only
   * proves survival when there is no ambient transaction at all - the case that holds today simply
   * because nothing wraps {@link AuditQueryService} in one. This test deliberately embeds the same
   * denied call in a real, rolled-back {@link TransactionTemplate} - the scenario a future
   * {@code @Transactional} caller would create - and proves the {@code DENIED} entry still
   * survives, thanks to {@code Propagation.NOT_SUPPORTED} on {@link
   * AuditEventRecorder#recordAuditLogAccess} (see that method's Javadoc).
   */
  @Test
  void theDeniedEntrySurvivesEvenWhenEmbeddedInARollingTransaction() {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status -> {
                      queryService.byTimeRange(
                          organizationId,
                          regularUserId,
                          REASON,
                          base,
                          base.plus(1, ChronoUnit.HOURS),
                          0,
                          50);
                      return null;
                    }))
        .isInstanceOf(AccessDeniedException.class);

    // The transaction the call above ran in rolled back (an uncaught exception inside
    // TransactionTemplate#execute marks it for rollback) - if recordAuditLogAccess had joined
    // that transaction instead of suspending it, this would find nothing.
    List<AuditLogEntry> selfLogEntries = findAuditLogAccessedEntries();
    assertThat(selfLogEntries).hasSize(1);
    assertThat(selfLogEntries.get(0).getOutcome()).isEqualTo(AuditOutcome.DENIED);
  }

  /**
   * Not a special store: the #394 self-log entries are ordinary {@code audit_log} rows, so an
   * AUDITOR reading them back (e.g. via {@code by-event-type=AUDIT_LOG_ACCESSED}) goes through the
   * exact same funnel and creates one more entry, on top of the ones already there - "wer ein
   * Protokoll führen will, das den Blick ins Protokoll ausnimmt, führt keines"
   * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt).
   */
  @Test
  void queryingTheSelfLogEntriesThemselvesCreatesAnotherOne() {
    queryService.byTimeRange(
        organizationId,
        auditorId,
        REASON,
        base.minus(1, ChronoUnit.HOURS),
        base.plus(1, ChronoUnit.HOURS),
        0,
        50);
    assertThat(findAuditLogAccessedEntries()).hasSize(1);

    Page<AuditLogEntry> result =
        queryService.byEventType(
            organizationId,
            auditorId,
            REASON,
            AuditEventType.AUDIT_LOG_ACCESSED,
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.HOURS),
            0,
            50);

    // The one entry from the by-time-range call above was already visible to this by-event-type
    // call (recorded before it ran); this call's own self-log entry commits only afterwards.
    assertThat(result.getContent()).hasSize(1);
    assertThat(findAuditLogAccessedEntries()).hasSize(2);
  }

  /**
   * The issue's own cross-cutting acceptance criterion, checked against every one of the five #393
   * access paths individually, not just {@code by-time-range}: "ein Test belegt, dass kein
   * Aufrufweg Protokolldaten liest, ohne einen Eintrag zu erzeugen". Each path is exercised twice -
   * once permitted (a valid AUDITOR call), once denied (a non-AUDITOR caller) - and both must leave
   * behind exactly one new self-log entry with the matching outcome. {@link #byIncidentScope} needs
   * an approved grant first, set up once here rather than per access path above.
   */
  @Test
  void everyAccessPathSelfLogsOnSuccessAndOnDenial() {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            organizationId,
            auditorId,
            regularUserId,
            base.minus(1, ChronoUnit.DAYS),
            base.plus(1, ChronoUnit.DAYS),
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            "Cross-cutting #394 Testaufbau");
    UUID otherAuditorId = createUser(SystemRole.AUDITOR);
    try {
      incidentScopeService.approve(organizationId, grant.getId(), otherAuditorId);

      Runnable[] permittedCalls = {
        () ->
            queryService.byObject(
                organizationId,
                auditorId,
                REASON,
                AuditObjectType.KNOWLEDGE_LIBRARY,
                "lib-cross-cutting",
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byTimeRange(
                organizationId,
                auditorId,
                REASON,
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byEventType(
                organizationId,
                auditorId,
                REASON,
                AuditEventType.LIBRARY_CREATED,
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byCorrelation(
                organizationId,
                auditorId,
                REASON,
                "cross-cutting-correlation",
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byIncidentScope(
                organizationId,
                auditorId,
                REASON,
                grant.getId(),
                base.minus(1, ChronoUnit.DAYS),
                base.plus(1, ChronoUnit.DAYS),
                0,
                50)
      };
      Runnable[] deniedCalls = {
        () ->
            queryService.byObject(
                organizationId,
                regularUserId,
                REASON,
                AuditObjectType.KNOWLEDGE_LIBRARY,
                "lib-cross-cutting",
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byTimeRange(
                organizationId,
                regularUserId,
                REASON,
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byEventType(
                organizationId,
                regularUserId,
                REASON,
                AuditEventType.LIBRARY_CREATED,
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byCorrelation(
                organizationId,
                regularUserId,
                REASON,
                "cross-cutting-correlation",
                base.minus(1, ChronoUnit.HOURS),
                base.plus(1, ChronoUnit.HOURS),
                0,
                50),
        () ->
            queryService.byIncidentScope(
                organizationId,
                regularUserId,
                REASON,
                grant.getId(),
                base.minus(1, ChronoUnit.DAYS),
                base.plus(1, ChronoUnit.DAYS),
                0,
                50)
      };

      int expectedCount = 0;
      for (Runnable call : permittedCalls) {
        call.run();
        expectedCount++;
        assertThat(findAuditLogAccessedEntries()).hasSize(expectedCount);
        assertThat(findAuditLogAccessedEntries().get(expectedCount - 1).getOutcome())
            .isEqualTo(AuditOutcome.SUCCESS);
      }
      for (Runnable call : deniedCalls) {
        assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
        expectedCount++;
        assertThat(findAuditLogAccessedEntries()).hasSize(expectedCount);
        assertThat(findAuditLogAccessedEntries().get(expectedCount - 1).getOutcome())
            .isEqualTo(AuditOutcome.DENIED);
      }
    } finally {
      // The grant references otherAuditorId as approver (and auditorId/regularUserId as
      // requester/subject) - must go first, or deleting the user violates
      // fk_audit_incident_scope_grants_approved_by (no cascade, unlike audit_actor_pseudonyms).
      jdbcTemplate.update(
          "DELETE FROM audit_incident_scope_grants WHERE organization_id = ?", organizationId);
      userRepository.deleteById(otherAuditorId);
    }
  }

  // Sorted by recordedAt (never actor, per #393) since findAll() itself makes no ordering
  // guarantee - callers that rely on insertion order (e.g.
  // everyAccessPathSelfLogsOnSuccessAndOnDenial)
  // need a deterministic one.
  private List<AuditLogEntry> findAuditLogAccessedEntries() {
    return auditLogRepository.findAll().stream()
        .filter(e -> e.getOrganizationId().equals(organizationId))
        .filter(e -> e.getEventType() == AuditEventType.AUDIT_LOG_ACCESSED)
        .sorted(Comparator.comparing(AuditLogEntry::getRecordedAt))
        .toList();
  }
}
