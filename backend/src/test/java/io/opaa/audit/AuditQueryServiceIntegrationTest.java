package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class AuditQueryServiceIntegrationTest {

  @Autowired private AuditQueryService queryService;
  @Autowired private AuditLogService auditLogService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
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
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
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
            organizationId, base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.DAYS), 0, 50);

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
                    base.minus(1, ChronoUnit.HOURS),
                    base.plus(1, ChronoUnit.HOURS),
                    -1,
                    50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aQueryWithoutAMandatoryTimeRangeIsRejected() {
    assertThatThrownBy(() -> queryService.byTimeRange(organizationId, null, base, 0, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queryService.byTimeRange(organizationId, base, null, 0, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                queryService.byTimeRange(
                    organizationId, base.plus(1, ChronoUnit.DAYS), base, 0, 50))
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
            organizationId, base.minus(1, ChronoUnit.HOURS), base.plus(3, ChronoUnit.HOURS), 0, 50);

    assertThat(result.getContent().stream().map(AuditLogEntry::getObjectId).toList())
        .containsExactly("a-first", "z-second", "m-third");
  }

  /**
   * The acceptance criterion's cross-cutting test: every access path {@link AuditQueryService}
   * exposes takes no actor/person parameter and cannot be asked to sort or group by one - proven
   * structurally (the method signatures below simply do not accept such a parameter) rather than by
   * trying every possible malicious input, which a closed API surface makes unnecessary.
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
}
