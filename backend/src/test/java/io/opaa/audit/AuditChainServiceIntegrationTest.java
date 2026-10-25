package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditChainReport.DeviationKind;
import io.opaa.audit.AuditChainReport.Status;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The checksum chain against the real schema (ADR-0058, Entscheidung 4): a run chains and seals an
 * ended month and writes the seal entry with the head hash; a second run writes nothing twice; the
 * verification tells an intact month from one whose entry was changed, removed or inserted after
 * the fact, in whichever segment; the chain follows the partition bounds, not the session time
 * zone, and covers every column the table has. The month under test lies two months back, inside
 * the partitions the baseline creates, and is cleared of checkpoints and seal before every test
 * because the scheduler of the shared context may have sealed it meanwhile.
 */
@OpaaIntegrationTest
class AuditChainServiceIntegrationTest {

  @Autowired private AuditChainService chainService;
  @Autowired private AuditLogService auditLogService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
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
        .contains("\"month\":\"" + month + "\"")
        .contains("\"algorithm\":\"" + AuditChainHasher.ALGORITHM + "\"");

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
  void aChangedSchemaVersionBreaksTheChain() {
    UUID only = writeEntry("lib-1", midMonth);
    chainService.run();

    jdbcTemplate.update("UPDATE audit_log SET schema_version = NULL WHERE event_id = ?", only);

    AuditChainReport report = chainService.verify(month);
    assertThat(report.status()).isEqualTo(Status.BROKEN);
    assertThat(report.deviation().kind()).isEqualTo(DeviationKind.CHANGED);
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

  /**
   * With a batch of two, five entries yield checkpoints at 2, 4 and 5; a change to the third entry
   * is found at the checkpoint 4 and placed in the segment between the second and the fourth entry.
   */
  @Test
  void aDeviationInAMiddleSegmentIsFoundAtTheNextCheckpointAndPlacedBetweenItsNeighbours() {
    AuditChainService smallBatches =
        new AuditChainService(
            jdbcTemplate,
            auditLogService,
            transactionManager,
            new SimpleMeterRegistry(),
            2,
            AuditChainService.SETTLE_DELAY);
    writeEntry("lib-1", midMonth);
    writeEntry("lib-2", midMonth.plusSeconds(1));
    UUID third = writeEntry("lib-3", midMonth.plusSeconds(2));
    writeEntry("lib-4", midMonth.plusSeconds(3));
    writeEntry("lib-5", midMonth.plusSeconds(4));

    AuditChainService.Run run = smallBatches.run();
    assertThat(run.sealedMonths()).contains(month);
    assertThat(checkpointPositions()).containsExactly(2L, 4L, 5L);
    AuditChainReport intact = chainService.verify(month);
    assertThat(intact.status()).isEqualTo(Status.INTACT);
    assertThat(intact.checkpoints()).isEqualTo(3);
    assertThat(intact.chainedEntries()).isEqualTo(5);

    jdbcTemplate.update("UPDATE audit_log SET outcome = 'FAILURE' WHERE event_id = ?", third);

    AuditChainReport report = chainService.verify(month);
    assertThat(report.status()).isEqualTo(Status.BROKEN);
    assertThat(report.deviation().kind()).isEqualTo(DeviationKind.CHANGED);
    assertThat(report.deviation().position()).isEqualTo(4);
    assertThat(report.deviation().segmentFrom()).isEqualTo(midMonth.plusSeconds(1));
    assertThat(report.deviation().segmentTo()).isEqualTo(midMonth.plusSeconds(3));
  }

  /**
   * Two runs without the lock (ADR-0053, Entscheidung 7) race for the same position: the second
   * writer's insert waits on the first's open transaction and then fails on the primary key, which
   * stops that run without an exception and without a seal.
   */
  @Test
  void aCheckpointAnotherRunWroteMeanwhileStopsThisRunQuietly() throws Exception {
    writeEntry("lib-1", midMonth);
    writeEntry("lib-2", midMonth.plusSeconds(1));
    writeEntry("lib-3", midMonth.plusSeconds(2));
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> other =
        CompletableFuture.runAsync(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          jdbcTemplate.update(
                              "INSERT INTO audit_chain_checkpoints (month, position,"
                                  + " last_recorded_at, last_event_id, head_hash, algorithm,"
                                  + " created_at) VALUES (?, 3, now(), gen_random_uuid(),"
                                  + " decode(repeat('ab', 32), 'hex'), ?, now())",
                              Date.valueOf(month.atDay(1)),
                              AuditChainHasher.ALGORITHM);
                          written.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(written.await(30, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<AuditChainService.Run> run = CompletableFuture.supplyAsync(chainService::run);
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!insertIntoCheckpointsIsBlocked() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertThat(insertIntoCheckpointsIsBlocked()).as("the run waits on the other writer").isTrue();
    } finally {
      release.countDown();
      other.get(30, TimeUnit.SECONDS);
    }

    assertThat(run.get(30, TimeUnit.SECONDS).sealedMonths()).doesNotContain(month);
    assertThat(checkpointPositions()).containsExactly(3L);
    assertThat(sealEventsOfTheMonth()).isZero();
  }

  private boolean insertIntoCheckpointsIsBlocked() {
    Long waiting =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                + " AND query LIKE 'INSERT INTO audit_chain_checkpoints%'",
            Long.class);
    return waiting != null && waiting > 0;
  }

  /**
   * A month whose checkpoints name a procedure this version does not know is neither continued nor
   * sealed, and its verification says so instead of failing.
   */
  @Test
  void aMonthUnderAnUnknownProcedureIsLeftAloneAndReportedUnverifiable() {
    writeEntry("lib-1", midMonth);
    jdbcTemplate.update(
        "INSERT INTO audit_chain_checkpoints (month, position, last_recorded_at, last_event_id,"
            + " head_hash, algorithm, created_at)"
            + " VALUES (?, 1, now(), gen_random_uuid(), decode(repeat('ab', 32), 'hex'),"
            + " 'OPAA-AUDIT-CHAIN-9', now())",
        Date.valueOf(month.atDay(1)));

    AuditChainService.Run run = chainService.run();
    assertThat(run.sealedMonths()).doesNotContain(month);
    assertThat(checkpointPositions()).containsExactly(1L);

    AuditChainReport report = chainService.verify(month);
    assertThat(report.status()).isEqualTo(Status.UNVERIFIABLE);
    assertThat(report.algorithm()).isEqualTo("OPAA-AUDIT-CHAIN-9");
    assertThat(report.checkpoints()).isEqualTo(1);
    assertThat(report.deviation()).isNull();
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
  void aMonthWithoutAPartitionIsNotRetained() {
    AuditChainReport report = chainService.verify(YearMonth.of(1999, 12));

    assertThat(report.status()).isEqualTo(Status.NOT_RETAINED);
    assertThat(report.sealed()).isFalse();
    assertThat(report.deviation()).isNull();
  }

  /**
   * The acceptance of the retention: the partition of a month beyond the retention is dropped with
   * its checkpoints and seal, the month answers {@code NOT_RETAINED}, and every month still held
   * stays {@code INTACT}. The old partition is created here as the owner role would have.
   */
  @Test
  void droppingAnExpiredPartitionLeavesTheRemainingMonthsIntact() {
    YearMonth old = YearMonth.now(ZoneOffset.UTC).minusMonths(40);
    Map<String, Object> settings =
        jdbcTemplate.queryForMap(
            "SELECT retention_months, last_cutoff, last_run_month FROM audit_retention_settings"
                + " WHERE id = 1");
    createOwnedPartition(old);
    try {
      writeEntry("lib-old", old.atDay(10).atStartOfDay().toInstant(ZoneOffset.UTC));
      writeEntry("lib-1", midMonth);
      writeEntry("lib-2", midMonth.plusSeconds(1));
      AuditChainService.Run run = chainService.run();
      assertThat(run.sealedMonths()).contains(old, month);
      assertThat(chainService.verify(old).status()).isEqualTo(Status.INTACT);

      List<String> dropped =
          jdbcTemplate.queryForList(
              "SELECT * FROM opaa_audit_delete_expired_partitions()", String.class);

      assertThat(dropped).contains(partitionName(old));
      assertThat(chainService.verify(old).status()).isEqualTo(Status.NOT_RETAINED);
      assertThat(chainService.verify(month).status()).isEqualTo(Status.INTACT);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM audit_chain_seals WHERE month = ?",
                  Long.class,
                  Date.valueOf(old.atDay(1))))
          .isZero();
    } finally {
      jdbcTemplate.execute("DROP TABLE IF EXISTS " + partitionName(old));
      clearChainOf(old);
      jdbcTemplate.update(
          "UPDATE audit_retention_settings SET retention_months = ?, last_cutoff = ?,"
              + " last_run_month = ?, updated_at = now() WHERE id = 1",
          settings.get("retention_months"),
          settings.get("last_cutoff"),
          settings.get("last_run_month"));
    }
  }

  /**
   * An entry of a still open transaction holds the chain of its month back: the run chains nothing
   * recorded after the start of that transaction, and after the commit both entries follow in their
   * order.
   */
  @Test
  void anEntryOfAStillOpenTransactionHoldsTheChainBack() throws Exception {
    YearMonth current = YearMonth.now(ZoneOffset.UTC);
    AuditChainService withoutDelay =
        new AuditChainService(
            jdbcTemplate,
            auditLogService,
            transactionManager,
            new SimpleMeterRegistry(),
            AuditChainService.BATCH_SIZE,
            Duration.ZERO);
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    UUID[] lateId = new UUID[1];
    CompletableFuture<Void> slow =
        CompletableFuture.runAsync(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          lateId[0] = record("lib-late").getEventId();
                          written.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(written.await(30, TimeUnit.SECONDS)).isTrue();
    AuditLogEntry younger = record("lib-young");
    try {
      withoutDelay.run();

      Map<String, Object> head = headOf(current);
      assertThat(head)
          .as("nothing recorded after the open transaction started is chained")
          .satisfiesAnyOf(
              h -> assertThat(h).isNull(),
              h ->
                  assertThat(((Timestamp) h.get("last_recorded_at")).toInstant())
                      .isBefore(younger.getRecordedAt()));
    } finally {
      release.countDown();
      slow.get(30, TimeUnit.SECONDS);
    }

    for (int attempt = 0; attempt < 3 && !covers(headOf(current), younger); attempt++) {
      withoutDelay.run();
    }
    assertThat(covers(headOf(current), younger)).isTrue();
    List<UUID> order =
        jdbcTemplate.queryForList(
            "SELECT event_id FROM audit_log WHERE organization_id = ?"
                + " ORDER BY recorded_at, event_id",
            UUID.class,
            organizationId);
    assertThat(order).containsExactly(lateId[0], younger.getEventId());
    assertThat(chainService.verify(current).status()).isIn(Status.INTACT, Status.UNCHAINED);
  }

  /**
   * The bounds of a partition are what the database stores for it, whatever the time zone of the
   * session that reads them.
   */
  @Test
  void thePartitionBoundsDoNotDependOnTheSessionTimeZone() {
    List<Map<String, Object>> tokyo = partitionsUnder("Asia/Tokyo");
    List<Map<String, Object>> losAngeles = partitionsUnder("America/Los_Angeles");

    assertThat(tokyo).isNotEmpty().hasSameSizeAs(losAngeles);
    for (int i = 0; i < tokyo.size(); i++) {
      assertThat(tokyo.get(i).get("month")).isEqualTo(losAngeles.get(i).get("month"));
      assertThat(((Timestamp) tokyo.get(i).get("month_start")).toInstant())
          .isEqualTo(((Timestamp) losAngeles.get(i).get("month_start")).toInstant());
      assertThat(((Timestamp) tokyo.get(i).get("month_end")).toInstant())
          .isEqualTo(((Timestamp) losAngeles.get(i).get("month_end")).toInstant());
    }
  }

  /**
   * A month chained by a session in one time zone verifies intact in a session of another; both run
   * on their own connection with {@code SET TIME ZONE}, as a misconfigured pool would.
   */
  @Test
  void aMonthChainedInOneTimeZoneVerifiesIntactInAnother() throws Exception {
    writeEntry("lib-1", midMonth);
    writeEntry("lib-2", midMonth.plusSeconds(1));
    try (Session tokyo = sessionIn("Asia/Tokyo");
        Session losAngeles = sessionIn("America/Los_Angeles")) {
      AuditChainService.Run run = tokyo.service().run();
      assertThat(run.sealedMonths()).contains(month);

      AuditChainReport report = losAngeles.service().verify(month);

      assertThat(report.status()).isEqualTo(Status.INTACT);
      assertThat(report.chainedEntries()).isEqualTo(2);
    }
  }

  /** The procedure covers every column of {@code audit_log}, the exact set, nothing more. */
  @Test
  void theChainCoversExactlyTheColumnsOfTheLog() {
    List<String> columns =
        jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'audit_log'"
                + " AND table_schema = current_schema() ORDER BY ordinal_position",
            String.class);

    assertThat(columns).containsExactlyInAnyOrderElementsOf(AuditChainHasher.COLUMNS);
  }

  @Test
  void theGaugesAreRefreshedFromTheDatabase() {
    writeEntry("lib-1", midMonth);

    chainService.refreshMetrics();
    assertThat(meterRegistry.get("opaa.audit.chain.lag").gauge().value())
        .as("an unchained entry two months back makes the lag at least a month")
        .isGreaterThan(28 * 86_400);
    assertThat(meterRegistry.get("opaa.audit.chain.unsealed.months").gauge().value())
        .isGreaterThanOrEqualTo(1);

    chainService.run();

    chainService.refreshMetrics();
    assertThat(meterRegistry.get("opaa.audit.chain.lag").gauge().value())
        .as("after the run every entry two months back is chained")
        .isLessThan(28 * 86_400);
  }

  private UUID writeEntry(String objectId, Instant recordedAt) {
    AuditLogEntry saved = record(objectId);
    jdbcTemplate.update(
        "UPDATE audit_log SET recorded_at = ? WHERE event_id = ?",
        Timestamp.from(recordedAt),
        saved.getEventId());
    return saved.getEventId();
  }

  private AuditLogEntry record(String objectId) {
    return auditLogService.record(
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
  }

  private List<Long> checkpointPositions() {
    return jdbcTemplate.queryForList(
        "SELECT position FROM audit_chain_checkpoints WHERE month = ? ORDER BY position",
        Long.class,
        Date.valueOf(month.atDay(1)));
  }

  private Map<String, Object> headOf(YearMonth month) {
    return jdbcTemplate
        .queryForList(
            "SELECT position, last_recorded_at, last_event_id FROM audit_chain_checkpoints"
                + " WHERE month = ? ORDER BY position DESC LIMIT 1",
            Date.valueOf(month.atDay(1)))
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static boolean covers(Map<String, Object> head, AuditLogEntry entry) {
    if (head == null) {
      return false;
    }
    Instant last = ((Timestamp) head.get("last_recorded_at")).toInstant();
    return last.isAfter(entry.getRecordedAt())
        || (last.equals(entry.getRecordedAt())
            && ((UUID) head.get("last_event_id")).compareTo(entry.getEventId()) >= 0);
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

  private static String partitionName(YearMonth month) {
    return "audit_log_" + month.getYear() + "_" + String.format("%02d", month.getMonthValue());
  }

  private void createOwnedPartition(YearMonth month) {
    String name = partitionName(month);
    jdbcTemplate.execute(
        "CREATE TABLE "
            + name
            + " PARTITION OF audit_log FOR VALUES FROM ('"
            + month.atDay(1)
            + "') TO ('"
            + month.plusMonths(1).atDay(1)
            + "')");
    jdbcTemplate.execute("ALTER TABLE " + name + " OWNER TO opaa_audit_owner");
  }

  private List<Map<String, Object>> partitionsUnder(String timeZone) {
    return jdbcTemplate.execute(
        (ConnectionCallback<List<Map<String, Object>>>)
            connection -> {
              try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE '" + timeZone + "'");
                try (ResultSet rs = statement.executeQuery(AuditChainService.PARTITIONS)) {
                  List<Map<String, Object>> rows = new ArrayList<>();
                  while (rs.next()) {
                    rows.add(
                        Map.of(
                            "month", rs.getObject("month", LocalDate.class),
                            "month_start", rs.getTimestamp("month_start"),
                            "month_end", rs.getTimestamp("month_end")));
                  }
                  return rows;
                } finally {
                  statement.execute("RESET TIME ZONE");
                }
              }
            });
  }

  private Session sessionIn(String timeZone) throws Exception {
    Connection connection = dataSource.getConnection();
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET TIME ZONE '" + timeZone + "'");
    }
    return new Session(connection);
  }

  /** One pooled connection under its own time zone, handed back to the pool reset. */
  private final class Session implements AutoCloseable {
    private final Connection connection;

    Session(Connection connection) {
      this.connection = connection;
    }

    AuditChainService service() {
      SingleConnectionDataSource single = new SingleConnectionDataSource(connection, true);
      return new AuditChainService(
          new JdbcTemplate(single),
          auditLogService,
          new JdbcTransactionManager(single),
          new SimpleMeterRegistry(),
          AuditChainService.BATCH_SIZE,
          AuditChainService.SETTLE_DELAY);
    }

    @Override
    public void close() throws Exception {
      try (Statement statement = connection.createStatement()) {
        statement.execute("RESET TIME ZONE");
      } finally {
        connection.close();
      }
    }
  }
}
