package io.opaa.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditChainHasher.ChainedEntry;
import io.opaa.audit.AuditChainReport.Deviation;
import io.opaa.audit.AuditChainReport.DeviationKind;
import io.opaa.audit.AuditChainReport.Status;
import io.opaa.organization.Organization;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checksum chain of the audit log, one chain per monthly partition (ADR-0059, Entscheidung 4):
 * {@link #run()} chains settled entries in the order {@code (recorded_at, event_id)} with a
 * checkpoint per batch and seals an ended month together with its {@code AUDIT_LOG_MONTH_SEALED}
 * entry; {@link #verify} recomputes a month against checkpoints and seal. A reader of every column
 * of {@code audit_log} that hands none of them out; the primary keys of checkpoint and seal are the
 * markers against a second run (ADR-0053, Entscheidung 7).
 */
@Component
class AuditChainService {

  private static final Logger log = LoggerFactory.getLogger(AuditChainService.class);

  /** Entries one checkpoint covers at most; one checkpoint is written per batch. */
  static final int BATCH_SIZE = 2_000;

  /** Entries one run chains at most before it yields to the next tick. */
  static final int MAX_ENTRIES_PER_RUN = 50_000;

  /** The small margin against clock jumps behind the start of the oldest open transaction. */
  static final Duration SETTLE_DELAY = Duration.ofSeconds(5);

  /** {@code actor_ref} of the seal entry: a process, never a person. */
  static final String ACTOR = "audit-chain";

  /** Seconds a gauge read may take before it reports {@code NaN} instead. */
  static final int METRICS_QUERY_TIMEOUT_SECONDS = 30;

  /**
   * The monthly partitions of the log with the bounds the database holds for them, cast back from
   * the bound expression with their offset, so a session time zone cannot shift them.
   */
  static final String PARTITIONS =
      "SELECT to_date(right(c.relname, 7), 'YYYY_MM') AS month, (b.m[1])::timestamptz AS month_start,"
          + " (b.m[2])::timestamptz AS month_end"
          + " FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid"
          + " CROSS JOIN LATERAL (SELECT regexp_match(pg_catalog.pg_get_expr(c.relpartbound, c.oid),"
          + " 'FROM \\(''([^'']*)''\\) TO \\(''([^'']*)''\\)') AS m) b"
          + " WHERE i.inhparent = to_regclass('audit_log')"
          + " AND c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$' ORDER BY month";

  private static final String ENTRIES_FROM =
      "FROM audit_log WHERE recorded_at >= ? AND recorded_at < ?";

  private static final String AFTER_KEY = " AND (recorded_at, event_id) > (?, ?)";

  private final JdbcTemplate jdbc;
  private final JdbcTemplate metricsJdbc;
  private final AuditLogService auditLogService;
  private final AuditSettledLimit settledLimit;
  private final TransactionTemplate transaction;
  private final TransactionTemplate repeatableRead;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final int batchSize;
  private final AtomicReference<Double> lagSeconds = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> unsealedMonths = new AtomicReference<>(Double.NaN);

  @Autowired
  AuditChainService(
      JdbcTemplate jdbc,
      AuditLogService auditLogService,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry) {
    this(jdbc, auditLogService, transactionManager, meterRegistry, BATCH_SIZE, SETTLE_DELAY);
  }

  AuditChainService(
      JdbcTemplate jdbc,
      AuditLogService auditLogService,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry,
      int batchSize,
      Duration settleDelay) {
    this.jdbc = jdbc;
    this.metricsJdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
    this.metricsJdbc.setQueryTimeout(METRICS_QUERY_TIMEOUT_SECONDS);
    this.auditLogService = auditLogService;
    this.settledLimit = new AuditSettledLimit(jdbc, settleDelay);
    this.transaction = new TransactionTemplate(transactionManager);
    this.repeatableRead = new TransactionTemplate(transactionManager);
    this.repeatableRead.setReadOnly(true);
    this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.batchSize = batchSize;
    Gauge.builder("opaa.audit.chain.lag", lagSeconds, AtomicReference::get)
        .baseUnit("seconds")
        .description("Age of the oldest audit log entry no chain checkpoint covers yet")
        .register(meterRegistry);
    Gauge.builder("opaa.audit.chain.unsealed.months", unsealedMonths, AtomicReference::get)
        .baseUnit("months")
        .description("Ended months of the audit log without a seal")
        .register(meterRegistry);
  }

  /**
   * One run: every unsealed month whose partition starts below the settled limit, oldest first,
   * chained up to the limit or {@link #MAX_ENTRIES_PER_RUN}, and sealed once ended and complete.
   *
   * @return what the run did, for the log and the tests
   */
  Run run() {
    AuditSettledLimit.Limit limit = settledLimit.read();
    Set<LocalDate> sealedMonths = sealedMonths(jdbc);
    int budget = MAX_ENTRIES_PER_RUN;
    long chained = 0;
    List<YearMonth> sealed = new ArrayList<>();
    for (Partition partition : partitions(jdbc)) {
      if (!partition.start().isBefore(limit.settled())
          || sealedMonths.contains(partition.first())) {
        continue;
      }
      YearMonth month = partition.month();
      if (!sealEvents(jdbc, month).isEmpty()) {
        log.error(
            "Audit chain of {} has an AUDIT_LOG_MONTH_SEALED entry but no seal; the month is not"
                + " sealed again - verify it",
            month);
        continue;
      }
      Head head = head(jdbc, partition.first()).orElseGet(() -> Head.start(month));
      Optional<AuditChainHasher> hasher = AuditChainHasher.forAlgorithm(head.algorithm());
      if (hasher.isEmpty()) {
        log.warn(
            "Audit chain of {} uses the procedure {}, which this version does not know; the month"
                + " stays as it is",
            month,
            head.algorithm());
        continue;
      }
      Instant upper = partition.end().isBefore(limit.settled()) ? partition.end() : limit.settled();
      boolean exhausted = false;
      while (budget > 0) {
        int batch = Math.min(batchSize, budget);
        List<ChainedEntry> entries = entriesAfter(head, partition.start(), upper, batch);
        if (entries.isEmpty()) {
          exhausted = true;
          break;
        }
        head = head.extend(hasher.get(), entries);
        if (!insertCheckpoint(partition.first(), head)) {
          log.warn("Audit chain of {} moved meanwhile; this run stops", month);
          return new Run(chained, sealed);
        }
        chained += entries.size();
        budget -= entries.size();
        if (entries.size() < batch) {
          exhausted = true;
          break;
        }
      }
      if (exhausted && !partition.end().isAfter(limit.settled()) && seal(partition, head)) {
        sealed.add(month);
      }
      if (budget <= 0) {
        break;
      }
    }
    if (chained > 0 || !sealed.isEmpty()) {
      log.info("Audit chain: {} entries chained, months sealed: {}", chained, sealed);
    }
    return new Run(chained, sealed);
  }

  /**
   * Recomputes the chain of {@code month} in one repeatable-read snapshot and compares it with
   * every checkpoint, the seal and the seal's {@code AUDIT_LOG_MONTH_SEALED} entry; see {@link
   * AuditChainReport}. A month whose partition is gone while checkpoints or seal remain, whose seal
   * row is gone while its seal entry remains, or whose seal entry no longer names the seal's hash
   * is {@link Status#BROKEN}.
   */
  AuditChainReport verify(YearMonth month) {
    LocalDate first = month.atDay(1);
    return repeatableRead.execute(
        status -> {
          List<Checkpoint> checkpoints = checkpoints(first);
          Seal seal = seal(first).orElse(null);
          List<SealEvent> sealEvents = sealEvents(jdbc, month);
          String algorithm =
              seal != null
                  ? seal.algorithm()
                  : checkpoints.isEmpty()
                      ? AuditChainHasher.ALGORITHM
                      : checkpoints.getLast().algorithm();
          long chainedEntries =
              seal != null
                  ? seal.entryCount()
                  : checkpoints.isEmpty() ? 0 : checkpoints.getLast().position();
          String headHash =
              checkpoints.isEmpty()
                  ? seal == null ? null : AuditChainHasher.hex(seal.headHash())
                  : AuditChainHasher.hex(checkpoints.getLast().headHash());
          UUID sealEventId =
              seal != null
                  ? seal.sealEventId()
                  : sealEvents.isEmpty() ? null : sealEvents.getFirst().eventId();
          Optional<Partition> partition = partition(jdbc, month);
          Optional<AuditChainHasher> hasher = AuditChainHasher.forAlgorithm(algorithm);
          Instant monthStart =
              partition
                  .map(Partition::start)
                  .orElseGet(() -> first.atStartOfDay().toInstant(ZoneOffset.UTC));
          Instant monthEnd =
              partition
                  .map(Partition::end)
                  .orElseGet(() -> first.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC));
          Deviation deviation = null;
          long count = chainedEntries;
          if (partition.isEmpty()) {
            if (seal == null && checkpoints.isEmpty()) {
              return report(
                  month,
                  Status.NOT_RETAINED,
                  seal,
                  0,
                  0,
                  checkpoints,
                  headHash,
                  null,
                  algorithm,
                  null);
            }
            // the partition is gone, but the retention would have taken checkpoints and seal with
            // it
            deviation =
                new Deviation(
                    chainedEntries, DeviationKind.INSERTED_OR_REMOVED, monthStart, monthEnd);
          } else if (hasher.isEmpty()) {
            return report(
                month,
                Status.UNVERIFIABLE,
                seal,
                chainedEntries,
                0,
                checkpoints,
                headHash,
                sealEventId,
                algorithm,
                null);
          } else {
            Recomputation recomputed =
                recompute(hasher.get(), month, partition.get(), checkpoints, seal);
            count = recomputed.count();
            deviation = recomputed.deviation();
          }
          if (deviation == null) {
            deviation = sealEventDeviation(seal, sealEvents, chainedEntries, monthStart, monthEnd);
          }
          Status verdict =
              deviation != null
                  ? Status.BROKEN
                  : checkpoints.isEmpty() && seal == null ? Status.UNCHAINED : Status.INTACT;
          return report(
              month,
              verdict,
              seal,
              chainedEntries,
              Math.max(0, count - chainedEntries),
              checkpoints,
              headHash,
              sealEventId,
              algorithm,
              deviation);
        });
  }

  private static AuditChainReport report(
      YearMonth month,
      Status status,
      Seal seal,
      long chainedEntries,
      long unchainedEntries,
      List<Checkpoint> checkpoints,
      String headHash,
      UUID sealEventId,
      String algorithm,
      Deviation deviation) {
    return new AuditChainReport(
        month,
        status,
        seal != null,
        chainedEntries,
        unchainedEntries,
        checkpoints.size(),
        headHash,
        sealEventId,
        algorithm,
        deviation);
  }

  /**
   * The seal and its {@code AUDIT_LOG_MONTH_SEALED} entry must name each other: a seal entry
   * without a seal row, a seal row without its entry, or an entry with another hash or count is a
   * deviation over the whole month.
   */
  private Deviation sealEventDeviation(
      Seal seal, List<SealEvent> sealEvents, long chainedEntries, Instant from, Instant to) {
    if (seal == null) {
      return sealEvents.isEmpty()
          ? null
          : new Deviation(chainedEntries, DeviationKind.INSERTED_OR_REMOVED, from, to);
    }
    Optional<SealEvent> own =
        sealEvents.stream().filter(e -> e.eventId().equals(seal.sealEventId())).findFirst();
    if (own.isEmpty()) {
      return new Deviation(seal.entryCount(), DeviationKind.INSERTED_OR_REMOVED, from, to);
    }
    JsonNode after = jsonMapper.readTree(own.get().after() == null ? "{}" : own.get().after());
    boolean matches =
        AuditChainHasher.hex(seal.headHash()).equals(after.path("headHash").asString(null))
            && after.path("entries").asLong(-1) == seal.entryCount();
    return matches ? null : new Deviation(seal.entryCount(), DeviationKind.CHANGED, from, to);
  }

  private Recomputation recompute(
      AuditChainHasher hasher,
      YearMonth month,
      Partition partition,
      List<Checkpoint> checkpoints,
      Seal seal) {
    Walk walk = new Walk(hasher, hasher.start(month), checkpoints, partition.start());
    jdbc.query(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT "
                      + AuditChainHasher.SELECT_COLUMNS
                      + " "
                      + ENTRIES_FROM
                      + " ORDER BY recorded_at, event_id");
          statement.setFetchSize(BATCH_SIZE);
          statement.setTimestamp(1, Timestamp.from(partition.start()));
          statement.setTimestamp(2, Timestamp.from(partition.end()));
          return statement;
        },
        (RowCallbackHandler) rs -> walk.accept(AuditChainHasher.read(rs)));
    Deviation deviation = walk.deviation();
    if (deviation == null && walk.nextCheckpoint() != null) {
      // the chain head counts more entries than the month holds
      deviation =
          new Deviation(
              walk.nextCheckpoint().position(),
              DeviationKind.INSERTED_OR_REMOVED,
              walk.segmentFrom(),
              walk.nextCheckpoint().lastRecordedAt());
    }
    if (deviation == null
        && seal != null
        && (seal.entryCount() != walk.count() || !Arrays.equals(seal.headHash(), walk.hash()))) {
      deviation =
          new Deviation(
              seal.entryCount(),
              seal.entryCount() == walk.count()
                  ? DeviationKind.CHANGED
                  : DeviationKind.INSERTED_OR_REMOVED,
              walk.segmentFrom(),
              partition.end());
    }
    return new Recomputation(walk.count(), deviation);
  }

  /** The recomputed chain, compared against the checkpoints as the entries stream past. */
  private static final class Walk {
    private final AuditChainHasher hasher;
    private final List<Checkpoint> checkpoints;
    private byte[] hash;
    private long count;
    private int next;
    private Instant segmentFrom;
    private Deviation deviation;

    Walk(AuditChainHasher hasher, byte[] start, List<Checkpoint> checkpoints, Instant monthStart) {
      this.hasher = hasher;
      this.hash = start;
      this.checkpoints = checkpoints;
      this.segmentFrom = monthStart;
    }

    void accept(ChainedEntry entry) {
      count++;
      hash = hasher.next(hash, entry);
      while (next < checkpoints.size() && checkpoints.get(next).position() == count) {
        Checkpoint checkpoint = checkpoints.get(next);
        boolean sameKey =
            Objects.equals(checkpoint.lastRecordedAt(), entry.recordedAt())
                && Objects.equals(checkpoint.lastEventId(), entry.eventId());
        if (deviation == null && !Arrays.equals(checkpoint.headHash(), hash)) {
          deviation =
              new Deviation(
                  checkpoint.position(),
                  sameKey ? DeviationKind.CHANGED : DeviationKind.INSERTED_OR_REMOVED,
                  segmentFrom,
                  checkpoint.lastRecordedAt());
        }
        segmentFrom = checkpoint.lastRecordedAt();
        next++;
      }
    }

    Checkpoint nextCheckpoint() {
      return next < checkpoints.size() ? checkpoints.get(next) : null;
    }

    Deviation deviation() {
      return deviation;
    }

    long count() {
      return count;
    }

    byte[] hash() {
      return hash;
    }

    Instant segmentFrom() {
      return segmentFrom;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Chaining
  // ---------------------------------------------------------------------------------------------

  private List<ChainedEntry> entriesAfter(Head head, Instant monthStart, Instant upper, int limit) {
    if (head.position() == 0) {
      return jdbc.query(
          "SELECT "
              + AuditChainHasher.SELECT_COLUMNS
              + " "
              + ENTRIES_FROM
              + " ORDER BY recorded_at, event_id LIMIT ?",
          (rs, row) -> AuditChainHasher.read(rs),
          Timestamp.from(monthStart),
          Timestamp.from(upper),
          limit);
    }
    return jdbc.query(
        "SELECT "
            + AuditChainHasher.SELECT_COLUMNS
            + " "
            + ENTRIES_FROM
            + AFTER_KEY
            + " ORDER BY recorded_at, event_id LIMIT ?",
        (rs, row) -> AuditChainHasher.read(rs),
        Timestamp.from(head.lastRecordedAt()),
        Timestamp.from(upper),
        Timestamp.from(head.lastRecordedAt()),
        head.lastEventId(),
        limit);
  }

  /** {@code false} when another run already wrote this position. */
  private boolean insertCheckpoint(LocalDate first, Head head) {
    try {
      return jdbc.update(
              "INSERT INTO audit_chain_checkpoints (month, position, last_recorded_at,"
                  + " last_event_id, head_hash, algorithm, created_at)"
                  + " VALUES (?, ?, ?, ?, ?, ?, clock_timestamp())",
              Date.valueOf(first),
              head.position(),
              Timestamp.from(head.lastRecordedAt()),
              head.lastEventId(),
              head.hash(),
              head.algorithm())
          == 1;
    } catch (DuplicateKeyException raced) {
      return false;
    }
  }

  /**
   * Seals the month of {@code partition} with {@code head} and records the {@code
   * AUDIT_LOG_MONTH_SEALED} entry in the same transaction; {@code false} when sealed already.
   */
  private boolean seal(Partition partition, Head head) {
    YearMonth month = partition.month();
    Boolean sealed =
        transaction.execute(
            status -> {
              Map<String, Object> after = new LinkedHashMap<>();
              after.put("month", month.toString());
              after.put("entries", head.position());
              after.put("headHash", AuditChainHasher.hex(head.hash()));
              after.put("algorithm", head.algorithm());
              AuditLogEntry event =
                  AuditLogEntry.withoutSubject(
                      Organization.DEFAULT_ID,
                      ActorKind.SYSTEM_PROCESS,
                      ACTOR,
                      AuditEventType.AUDIT_LOG_MONTH_SEALED,
                      AuditObjectType.AUDIT_LOG,
                      month.toString(),
                      null,
                      null,
                      jsonMapper.writeValueAsString(after),
                      AuditOutcome.SUCCESS,
                      null,
                      null);
              int inserted =
                  jdbc.update(
                      "INSERT INTO audit_chain_seals (month, entry_count, head_hash, algorithm,"
                          + " sealed_at, seal_event_id) VALUES (?, ?, ?, ?, clock_timestamp(), ?)"
                          + " ON CONFLICT (month) DO NOTHING",
                      Date.valueOf(partition.first()),
                      head.position(),
                      head.hash(),
                      head.algorithm(),
                      event.getEventId());
              if (inserted == 0) {
                status.setRollbackOnly();
                return false;
              }
              auditLogService.record(event);
              return true;
            });
    return Boolean.TRUE.equals(sealed);
  }

  // ---------------------------------------------------------------------------------------------
  // Partitions, checkpoints and seals
  // ---------------------------------------------------------------------------------------------

  private static List<Partition> partitions(JdbcTemplate jdbc) {
    return jdbc.query(
        PARTITIONS,
        (rs, row) ->
            new Partition(
                YearMonth.from(rs.getObject("month", LocalDate.class)),
                rs.getTimestamp("month_start").toInstant(),
                rs.getTimestamp("month_end").toInstant()));
  }

  private static Optional<Partition> partition(JdbcTemplate jdbc, YearMonth month) {
    return partitions(jdbc).stream().filter(p -> p.month().equals(month)).findFirst();
  }

  /** The {@code AUDIT_LOG_MONTH_SEALED} entries written for {@code month}, oldest first. */
  private static List<SealEvent> sealEvents(JdbcTemplate jdbc, YearMonth month) {
    return jdbc.query(
        "SELECT event_id, after FROM audit_log WHERE organization_id = ? AND object_type = ?"
            + " AND object_id = ? AND event_type = ? ORDER BY recorded_at, event_id",
        (rs, row) -> new SealEvent(rs.getObject("event_id", UUID.class), rs.getString("after")),
        Organization.DEFAULT_ID,
        AuditObjectType.AUDIT_LOG.name(),
        month.toString(),
        AuditEventType.AUDIT_LOG_MONTH_SEALED.name());
  }

  private static Set<LocalDate> sealedMonths(JdbcTemplate jdbc) {
    return new HashSet<>(jdbc.queryForList("SELECT month FROM audit_chain_seals", LocalDate.class));
  }

  private static Optional<Head> head(JdbcTemplate jdbc, LocalDate first) {
    return jdbc
        .query(
            "SELECT position, last_recorded_at, last_event_id, head_hash, algorithm"
                + " FROM audit_chain_checkpoints WHERE month = ? ORDER BY position DESC LIMIT 1",
            (rs, row) -> head(rs),
            Date.valueOf(first))
        .stream()
        .findFirst();
  }

  private List<Checkpoint> checkpoints(LocalDate first) {
    return jdbc.query(
        "SELECT position, last_recorded_at, last_event_id, head_hash, algorithm"
            + " FROM audit_chain_checkpoints WHERE month = ? ORDER BY position",
        (rs, row) -> checkpoint(rs),
        Date.valueOf(first));
  }

  private Optional<Seal> seal(LocalDate first) {
    return jdbc
        .query(
            "SELECT entry_count, head_hash, algorithm, seal_event_id FROM audit_chain_seals"
                + " WHERE month = ?",
            (rs, row) ->
                new Seal(
                    rs.getLong("entry_count"),
                    rs.getBytes("head_hash"),
                    rs.getString("algorithm"),
                    rs.getObject("seal_event_id", UUID.class)),
            Date.valueOf(first))
        .stream()
        .findFirst();
  }

  private static Head head(ResultSet rs) throws SQLException {
    Checkpoint checkpoint = checkpoint(rs);
    return new Head(
        checkpoint.position(),
        checkpoint.lastRecordedAt(),
        checkpoint.lastEventId(),
        checkpoint.headHash(),
        checkpoint.algorithm());
  }

  private static Checkpoint checkpoint(ResultSet rs) throws SQLException {
    Timestamp lastRecordedAt = rs.getTimestamp("last_recorded_at");
    return new Checkpoint(
        rs.getLong("position"),
        lastRecordedAt == null ? null : lastRecordedAt.toInstant().truncatedTo(ChronoUnit.MICROS),
        rs.getObject("last_event_id", UUID.class),
        rs.getBytes("head_hash"),
        rs.getString("algorithm"));
  }

  // ---------------------------------------------------------------------------------------------
  // Gauges
  // ---------------------------------------------------------------------------------------------

  /**
   * Reads both gauges anew. Runs on every instance without a lock: it only reads, and each instance
   * reports its own gauges (ScheduledRunLockStructureTest#RUNS_ON_EVERY_INSTANCE); a read that
   * fails or exceeds {@link #METRICS_QUERY_TIMEOUT_SECONDS} reports {@code NaN}.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "PT5M")
  public void refreshMetrics() {
    try {
      Instant now =
          metricsJdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
      Set<LocalDate> sealedMonths = sealedMonths(metricsJdbc);
      Instant oldest = null;
      long ended = 0;
      for (Partition partition : partitions(metricsJdbc)) {
        if (sealedMonths.contains(partition.first()) || !partition.start().isBefore(now)) {
          continue;
        }
        if (!partition.end().isAfter(now)) {
          ended++;
        }
        Timestamp candidate = oldestUnchained(partition);
        if (candidate != null && (oldest == null || candidate.toInstant().isBefore(oldest))) {
          oldest = candidate.toInstant();
        }
      }
      lagSeconds.set(
          oldest == null ? 0.0 : (double) Math.max(0, Duration.between(oldest, now).toSeconds()));
      unsealedMonths.set((double) ended);
    } catch (RuntimeException e) {
      log.warn("Audit chain metrics could not be read and report NaN: {}", e.getMessage());
      lagSeconds.set(Double.NaN);
      unsealedMonths.set(Double.NaN);
    }
  }

  private Timestamp oldestUnchained(Partition partition) {
    Head head = head(metricsJdbc, partition.first()).orElse(null);
    if (head == null) {
      return metricsJdbc.queryForObject(
          "SELECT min(recorded_at) " + ENTRIES_FROM,
          Timestamp.class,
          Timestamp.from(partition.start()),
          Timestamp.from(partition.end()));
    }
    return metricsJdbc.queryForObject(
        "SELECT min(recorded_at) " + ENTRIES_FROM + AFTER_KEY,
        Timestamp.class,
        Timestamp.from(head.lastRecordedAt()),
        Timestamp.from(partition.end()),
        Timestamp.from(head.lastRecordedAt()),
        head.lastEventId());
  }

  // ---------------------------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------------------------

  /** What one run did. */
  record Run(long chainedEntries, List<YearMonth> sealedMonths) {}

  /** A monthly partition with the bounds the database holds for it. */
  record Partition(YearMonth month, Instant start, Instant end) {
    LocalDate first() {
      return month.atDay(1);
    }
  }

  /** The chain head of a month: the position and key of the last chained entry and its hash. */
  private record Head(
      long position, Instant lastRecordedAt, UUID lastEventId, byte[] hash, String algorithm) {

    static Head start(YearMonth month) {
      return new Head(
          0, null, null, AuditChainHasher.CURRENT.start(month), AuditChainHasher.ALGORITHM);
    }

    Head extend(AuditChainHasher hasher, List<ChainedEntry> entries) {
      byte[] next = hash;
      for (ChainedEntry entry : entries) {
        next = hasher.next(next, entry);
      }
      ChainedEntry last = entries.getLast();
      return new Head(
          position + entries.size(), last.recordedAt(), last.eventId(), next, algorithm);
    }
  }

  private record Checkpoint(
      long position, Instant lastRecordedAt, UUID lastEventId, byte[] headHash, String algorithm) {}

  private record Seal(long entryCount, byte[] headHash, String algorithm, UUID sealEventId) {}

  private record SealEvent(UUID eventId, String after) {}

  private record Recomputation(long count, Deviation deviation) {}
}
