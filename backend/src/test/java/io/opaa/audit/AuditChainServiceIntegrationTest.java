package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditChainReport.DeviationKind;
import io.opaa.audit.AuditChainReport.Status;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The checksum chain against the real schema (ADR-0058, Entscheidung 4): a run chains and seals an
 * ended month and writes the seal entry with the head hash; a second run writes nothing twice; the
 * verification tells an intact month from one whose entry was changed, removed or inserted after
 * the fact. The month under test lies two months back, inside the partitions the baseline creates,
 * and is cleared of checkpoints and seal before every test because the scheduler of the shared
 * context may have sealed it meanwhile.
 */
@OpaaIntegrationTest
class AuditChainServiceIntegrationTest {

  @Autowired private AuditChainService chainService;
  @Autowired private AuditLogService auditLogService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  private final YearMonth month = YearMonth.now(ZoneOffset.UTC).minusMonths(2);
  private final Instant midMonth = month.atDay(15).atTime(12, 0).toInstant(ZoneOffset.UTC);

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Chain Test Org")).getId();
    clearChainOf(month);
  }

  @AfterEach
  void tearDown() {
    clearChainOf(month);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  private void clearChainOf(YearMonth month) {
    Date first = Date.valueOf(month.atDay(1));
    jdbcTemplate.update("DELETE FROM audit_chain_checkpoints WHERE month = ?", first);
    jdbcTemplate.update("DELETE FROM audit_chain_seals WHERE month = ?", first);
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type = 'AUDIT_LOG_MONTH_SEALED' AND object_id = ?",
        month.toString());
  }

  @Test
  void aRunChainsAndSealsAnEndedMonthAndWritesTheSealEntryWithTheHeadHash() {
    writeEntry("lib-1", midMonth);
    writeEntry("lib-2", midMonth.plusSeconds(1));
    writeEntry("lib-3", midMonth.plusSeconds(2));

    AuditChainService.Run run = chainService.run();

    assertThat(run.sealedMonths()).contains(month);
    assertThat(run.chainedEntries()).isGreaterThanOrEqualTo(3);
    Map<String, Object> seal =
        jdbcTemplate.queryForMap(
            "SELECT entry_count, encode(head_hash, 'hex') AS head_hash, algorithm, seal_event_id"
                + " FROM audit_chain_seals WHERE month = ?",
            Date.valueOf(month.atDay(1)));
    assertThat(seal.get("entry_count")).isEqualTo(3L);
    assertThat(seal.get("algorithm")).isEqualTo(AuditChainHasher.ALGORITHM);
    assertThat(checkpointPositions()).containsExactly(3L);

    Map<String, Object> sealEvent =
        jdbcTemplate.queryForMap(
            "SELECT organization_id, actor_kind, actor_ref, object_type, object_id, after,"
                + " outcome FROM audit_log WHERE event_id = ?",
            seal.get("seal_event_id"));
    assertThat(sealEvent.get("organization_id")).isEqualTo(Organization.DEFAULT_ID);
    assertThat(sealEvent.get("actor_kind")).isEqualTo("SYSTEM_PROCESS");
    assertThat(sealEvent.get("actor_ref")).isEqualTo(AuditChainService.ACTOR);
    assertThat(sealEvent.get("object_type")).isEqualTo("AUDIT_LOG");
    assertThat(sealEvent.get("object_id")).isEqualTo(month.toString());
    assertThat(sealEvent.get("outcome")).isEqualTo("SUCCESS");
    assertThat((String) sealEvent.get("after"))
        .contains("\"headHash\":\"" + seal.get("head_hash") + "\"")
        .contains("\"entries\":3")
        .contains("\"month\":\"" + month + "\"");

    AuditChainReport report = chainService.verify(month);
    assertThat(report.status()).isEqualTo(Status.INTACT);
    assertThat(report.sealed()).isTrue();
    assertThat(report.chainedEntries()).isEqualTo(3);
    assertThat(report.unchainedEntries()).isZero();
    assertThat(report.checkpoints()).isEqualTo(1);
    assertThat(report.headHash()).isEqualTo(seal.get("head_hash"));
    assertThat(report.sealEventId()).isEqualTo(seal.get("seal_event_id"));
    assertThat(report.deviation()).isNull();
  }

  @Test
  void aSecondRunWritesNeitherACheckpointNorASealAgain() {
    writeEntry("lib-1", midMonth);
    chainService.run();
    long sealEvents = sealEventsOfTheMonth();

    AuditChainService.Run second = chainService.run();

    assertThat(second.sealedMonths()).doesNotContain(month);
    assertThat(checkpointPositions()).containsExactly(1L);
    assertThat(sealEventsOfTheMonth()).isEqualTo(sealEvents).isEqualTo(1);
  }

  @Test
  void aChangedEntryBreaksTheChainAndNamesItsSegment() {
    writeEntry("lib-1", midMonth);
    UUID second = writeEntry("lib-2", midMonth.plusSeconds(1));
    writeEntry("lib-3", midMonth.plusSeconds(2));
    chainService.run();

    jdbcTemplate.update("UPDATE audit_log SET outcome = 'FAILURE' WHERE event_id = ?", second);

    AuditChainReport report = chainService.verify(month);
    assertThat(report.status()).isEqualTo(Status.BROKEN);
    assertThat(report.deviation()).isNotNull();
    assertThat(report.deviation().kind()).isEqualTo(DeviationKind.CHANGED);
    assertThat(report.deviation().position()).isEqualTo(3);
    assertThat(report.deviation().segmentFrom()).isBeforeOrEqualTo(midMonth);
    assertThat(report.deviation().segmentTo()).isAfterOrEqualTo(midMonth.plusSeconds(2));
  }

  @Test
  void aRemovedEntryBreaksTheChain() {
    writeEntry("lib-1", midMonth);
    UUID second = writeEntry("lib-2", midMonth.plusSeconds(1));
    writeEntry("lib-3", midMonth.plusSeconds(2));
    chainService.run();

    jdbcTemplate.update("DELETE FROM audit_log WHERE event_id = ?", second);

    AuditChainReport report = chainService.verify(month);
    assertThat(report.status()).isEqualTo(Status.BROKEN);
    assertThat(report.deviation().kind()).isEqualTo(DeviationKind.INSERTED_OR_REMOVED);
    assertThat(report.chainedEntries()).isEqualTo(3);
  }

  @Test
  void anEntryInsertedAfterTheSealBreaksTheChain() {
    writeEntry("lib-1", midMonth);
    writeEntry("lib-3", midMonth.plusSeconds(2));
    chainService.run();

    writeEntry("lib-2", midMonth.plusSeconds(1));

    AuditChainReport report = chainService.verify(month);
    assertThat(report.status()).isEqualTo(Status.BROKEN);
    assertThat(report.deviation().kind()).isEqualTo(DeviationKind.INSERTED_OR_REMOVED);
    assertThat(report.chainedEntries()).isEqualTo(2);
  }

  @Test
  void aMonthNoRunHasReachedIsUnchained() {
    YearMonth ahead = YearMonth.now(ZoneOffset.UTC).plusYears(3);

    AuditChainReport report = chainService.verify(ahead);

    assertThat(report.status()).isEqualTo(Status.UNCHAINED);
    assertThat(report.sealed()).isFalse();
    assertThat(report.chainedEntries()).isZero();
    assertThat(report.checkpoints()).isZero();
    assertThat(report.headHash()).isNull();
    assertThat(report.algorithm()).isEqualTo(AuditChainHasher.ALGORITHM);
  }

  @Test
  void theGaugesReadFromTheDatabase() {
    writeEntry("lib-1", midMonth);

    assertThat(meterRegistry.get("opaa.audit.chain.lag").gauge().value())
        .as("an unchained entry two months back makes the lag at least a month")
        .isGreaterThan(28 * 86_400);
    assertThat(meterRegistry.get("opaa.audit.chain.unsealed.months").gauge().value())
        .isGreaterThanOrEqualTo(1);

    chainService.run();

    assertThat(meterRegistry.get("opaa.audit.chain.lag").gauge().value())
        .as("after the run every entry two months back is chained")
        .isLessThan(28 * 86_400);
  }

  private UUID writeEntry(String objectId, Instant recordedAt) {
    AuditLogEntry saved =
        auditLogService.record(
            AuditLogEntry.withoutSubject(
                organizationId,
                ActorKind.USER,
                "pseud-" + objectId,
                AuditEventType.LIBRARY_CREATED,
                AuditObjectType.KNOWLEDGE_LIBRARY,
                objectId,
                "Bibliothek " + objectId,
                null,
                "{\"name\":\"" + objectId + "\"}",
                AuditOutcome.SUCCESS,
                null,
                null));
    jdbcTemplate.update(
        "UPDATE audit_log SET recorded_at = ? WHERE event_id = ?",
        Timestamp.from(recordedAt),
        saved.getEventId());
    return saved.getEventId();
  }

  private List<Long> checkpointPositions() {
    return jdbcTemplate.queryForList(
        "SELECT position FROM audit_chain_checkpoints WHERE month = ? ORDER BY position",
        Long.class,
        Date.valueOf(month.atDay(1)));
  }

  private long sealEventsOfTheMonth() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = 'AUDIT_LOG_MONTH_SEALED'"
                + " AND object_id = ?",
            Long.class,
            month.toString());
    return count == null ? 0 : count;
  }
}
