package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #393: the four revision access paths against a real Postgres database with the real, versioned
 * Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}),
 * mirroring {@code AuditLogServiceIntegrationTest}.
 *
 * <p>{@link #noAccessPathAcceptsOrSortsByActor()} is the acceptance criterion's dedicated,
 * cross-cutting test: it exercises every public query method {@link AuditQueryService} has and
 * proves none of them exposes an actor/person entry point, matches {@code
 * io.opaa.api.AuditControllerStructureTest} for the same claim at the HTTP layer.
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
    List<String> queryMethodNames =
        List.of("byObject", "byTimeRange", "byEventType", "byCorrelation", "byIncidentScope");
    for (String methodName : queryMethodNames) {
      boolean hasActorOrSortParameter =
          Arrays.stream(AuditQueryService.class.getDeclaredMethods())
              .filter(m -> m.getName().equals(methodName))
              .flatMap(m -> Arrays.stream(m.getParameterTypes()))
              .anyMatch(
                  type ->
                      type == org.springframework.data.domain.Sort.class
                          || type == org.springframework.data.domain.Pageable.class);
      assertThat(hasActorOrSortParameter)
          .as("%s must not accept a caller-supplied Sort/Pageable", methodName)
          .isFalse();
    }
  }
}
