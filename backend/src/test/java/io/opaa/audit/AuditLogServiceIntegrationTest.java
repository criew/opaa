package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}) - see #288 - and a real {@link
 * PlatformTransactionManager}, not a mocked one: the whole point of {@link
 * #anEntryWrittenInARolledBackTransactionIsNotPersisted()} is to exercise real commit/rollback
 * visibility, which a mocked transaction manager does not provide (see the developer role
 * contract's Transaktionen section, and #280/#297/#299 for what a mock misses).
 *
 * <p>Proves the transaction behaviour {@link AuditLogService} documents: {@link
 * AuditLogService#record} is not its own transaction, it joins whatever transaction the caller
 * already has open.
 */
@OpaaIntegrationTest
class AuditLogServiceIntegrationTest {

  @Autowired private AuditLogService auditLogService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private TransactionTemplate transactionTemplate;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Audit Test Org")).getId();
    transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void tearDown() {
    // fk_audit_log_organization is ON DELETE RESTRICT, so every entry written against
    // organizationId in a test must be removed first. This goes through JdbcTemplate, not
    // auditLogRepository: AuditLogEntry.isNew() is unconditionally true (see its Javadoc), which
    // makes Spring Data JPA's own delete/deleteAll a silent no-op for it by design - the same
    // property that keeps this repository "insert-only" at the Java layer, not only at the
    // database layer, would otherwise defeat this cleanup. This test's own Spring-managed
    // connection (Testcontainers' bootstrap account, a Postgres superuser - see
    // TestcontainersConfiguration) is not subject to the DML restriction migration 017 applies to
    // the real application account, so this cleanup call succeeds here even though it would not
    // in production; the restriction itself is verified separately, against a real non-superuser
    // role, by io.opaa.migration.Migration017AuditLogTest.
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void aFullRecordCanBeWrittenAndReadBackThroughTheService() {
    AuditLogEntry entry =
        AuditLogEntry.withSubject(
            organizationId,
            ActorKind.USER,
            "pseud-actor-1",
            AuditEventType.ASSET_GRANT_REVOKED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            "lib-personalvorgaenge",
            "Personalvorgaenge",
            AuditSubjectKind.GROUP,
            "pseud-subject-1",
            "{\"role\":\"READER\"}",
            null,
            AuditOutcome.SUCCESS,
            "anlassbezogene Klaerung",
            "sync-2026-02-16-06");

    AuditLogEntry saved = auditLogService.record(entry);

    AuditLogEntry read = auditLogRepository.findById(saved.getEventId()).orElseThrow();
    assertThat(read.getOrganizationId()).isEqualTo(organizationId);
    assertThat(read.getActorKind()).isEqualTo(ActorKind.USER);
    assertThat(read.getActorRef()).isEqualTo("pseud-actor-1");
    assertThat(read.getEventType()).isEqualTo(AuditEventType.ASSET_GRANT_REVOKED);
    assertThat(read.getObjectType()).isEqualTo(AuditObjectType.KNOWLEDGE_LIBRARY);
    assertThat(read.getObjectId()).isEqualTo("lib-personalvorgaenge");
    assertThat(read.getObjectLabel()).isEqualTo("Personalvorgaenge");
    assertThat(read.getSubjectKind()).isEqualTo(AuditSubjectKind.GROUP);
    assertThat(read.getSubjectRef()).isEqualTo("pseud-subject-1");
    assertThat(read.getBefore()).isEqualTo("{\"role\":\"READER\"}");
    assertThat(read.getAfter()).isNull();
    assertThat(read.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(read.getReason()).isEqualTo("anlassbezogene Klaerung");
    assertThat(read.getCorrelationRef()).isEqualTo("sync-2026-02-16-06");
    assertThat(read.getRecordedAt()).isNotNull();
  }

  @Test
  void anEntryWrittenInARolledBackTransactionIsNotPersisted() {
    UUID[] eventId = new UUID[1];

    transactionTemplate.execute(
        new TransactionCallbackWithoutResult() {
          @Override
          protected void doInTransactionWithoutResult(TransactionStatus status) {
            AuditLogEntry saved = auditLogService.record(minimalEntry());
            eventId[0] = saved.getEventId();
            status.setRollbackOnly();
          }
        });

    assertThat(auditLogRepository.findById(eventId[0])).isEmpty();
  }

  @Test
  void anEntryWrittenInACommittedTransactionIsPersisted() {
    UUID[] eventId = new UUID[1];

    transactionTemplate.execute(
        new TransactionCallbackWithoutResult() {
          @Override
          protected void doInTransactionWithoutResult(TransactionStatus status) {
            AuditLogEntry saved = auditLogService.record(minimalEntry());
            eventId[0] = saved.getEventId();
          }
        });

    assertThat(auditLogRepository.findById(eventId[0])).isPresent();
  }

  @Test
  void anEntryWrittenWithNoAmbientTransactionIsPersistedImmediately() {
    AuditLogEntry saved = auditLogService.record(minimalEntry());

    assertThat(auditLogRepository.findById(saved.getEventId())).isPresent();
  }

  private AuditLogEntry minimalEntry() {
    return AuditLogEntry.withoutSubject(
        organizationId,
        ActorKind.USER,
        "pseud-actor-1",
        AuditEventType.SPACE_CREATED,
        AuditObjectType.SPACE,
        "space-1",
        "Space 1",
        null,
        null,
        AuditOutcome.SUCCESS,
        null,
        null);
  }
}
