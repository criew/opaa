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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checksum chain of the audit log, one chain per monthly partition (ADR-0058, Entscheidung 4).
 * {@link #run()} chains, in the order {@code (recorded_at, event_id)}, every entry below the
 * settled limit of {@link AuditSettledLimit} that no checkpoint covers yet, writes a checkpoint per
 * batch and seals a month once it has ended and is chained to its end: the seal row and the {@code
 * AUDIT_LOG_MONTH_SEALED} entry that carries the head hash out of the database are written in one
 * transaction. The run is guarded by the scheduler lock of {@link AuditChainScheduler}; the primary
 * keys of checkpoint and seal are the markers that keep a second run from writing a position or a
 * seal twice (ADR-0053, Entscheidung 7). {@link #verify} recomputes the chain of one month and
 * compares it with every checkpoint and the seal.
 *
 * <p>A reader of {@code audit_log} beside the revision's access paths, the self-disclosure and the
 * SIEM export: it reads every column, because every column is chained, and gives none of it out - a
 * report names counts, hashes and a time segment, never an entry. The gauges report the age of the
 * oldest entry no checkpoint covers and the number of ended months without a seal; both come from
 * the database at every scrape, so every instance reports the same value (ADR-0053).
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

  /** The monthly partitions of the log, as the first day of their month. */
  private static final String PARTITION_MONTHS =
      "SELECT to_date(right(c.relname, 7), 'YYYY_MM') AS month"
          + " FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid"
          + " WHERE i.inhparent = to_regclass('audit_log')"
          + " AND c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$'";

  private static final String UNSEALED_MONTHS_BELOW =
      "SELECT month FROM ("
          + PARTITION_MONTHS
          + ") p WHERE p.month::timestamptz < ? AND NOT EXISTS"
          + " (SELECT 1 FROM audit_chain_seals s WHERE s.month = p.month) ORDER BY month";

  private static final String ENDED_UNSEALED_MONTHS =
      "SELECT count(*) FROM ("
          + PARTITION_MONTHS
          + ") p WHERE (p.month + interval '1 month')::timestamptz <= now() AND NOT EXISTS"
          + " (SELECT 1 FROM audit_chain_seals s WHERE s.month = p.month)";

  private static final String ENTRIES_FROM =
      "FROM audit_log WHERE recorded_at >= ? AND recorded_at < ?";

  private static final String AFTER_KEY = " AND (recorded_at, event_id) > (?, ?)";

  private final JdbcTemplate jdbc;
  private final AuditLogService auditLogService;
  private final AuditSettledLimit settledLimit;
  private final TransactionTemplate transaction;
  private final TransactionTemplate readOnly;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  AuditChainService(
      JdbcTemplate jdbc,
      AuditLogService auditLogService,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.auditLogService = auditLogService;
    this.settledLimit = new AuditSettledLimit(jdbc, SETTLE_DELAY);
    this.transaction = new TransactionTemplate(transactionManager);
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
    gauge(
        meterRegistry,
        "opaa.audit.chain.lag",
        "seconds",
        "Age of the oldest audit log entry no chain checkpoint covers yet",
        AuditChainService::lagSeconds);
    gauge(
        meterRegistry,
        "opaa.audit.chain.unsealed.months",
        "months",
        "Ended months of the audit log without a seal",
        AuditChainService::endedUnsealedMonths);
  }

  /**
   * One run: every unsealed month whose start lies below the settled limit, oldest first, chained
   * up to the limit or {@link #MAX_ENTRIES_PER_RUN}, and sealed once ended and complete.
   *
   * @return what the run did, for the log and the tests
   */
  Run run() {
    AuditSettledLimit.Limit limit = settledLimit.read();
    int budget = MAX_ENTRIES_PER_RUN;
    long chained = 0;
    List<YearMonth> sealed = new ArrayList<>();
    for (LocalDate first : unsealedMonthsBelow(limit.settled())) {
      YearMonth month = YearMonth.from(first);
      Bounds bounds = bounds(first);
      Head head = head(first).orElseGet(() -> Head.start(month));
      if (!AuditChainHasher.ALGORITHM.equals(head.algorithm())) {
        log.warn(
            "Audit chain of {} was started with algorithm {}, which this version does not"
                + " continue; the month stays as it is",
            month,
            head.algorithm());
        continue;
      }
      Instant upper = bounds.end().isBefore(limit.settled()) ? bounds.end() : limit.settled();
      boolean exhausted = false;
      while (budget > 0) {
        int batch = Math.min(BATCH_SIZE, budget);
        List<ChainedEntry> entries = entriesAfter(head, bounds.start(), upper, batch);
        if (entries.isEmpty()) {
          exhausted = true;
          break;
        }
        head = head.extend(entries);
        if (!insertCheckpoint(first, head)) {
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
      if (exhausted && !bounds.end().isAfter(limit.settled()) && seal(month, first, head)) {
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
   * Recomputes the chain of {@code month} in one read-only snapshot and compares it with every
   * checkpoint and the seal; see {@link AuditChainReport}.
   */
  AuditChainReport verify(YearMonth month) {
    LocalDate first = month.atDay(1);
    return readOnly.execute(
        status -> {
          Bounds bounds = bounds(first);
          List<Checkpoint> checkpoints = checkpoints(first);
          Seal seal = seal(first).orElse(null);
          String algorithm =
              seal != null
                  ? seal.algorithm()
                  : checkpoints.isEmpty()
                      ? AuditChainHasher.ALGORITHM
                      : checkpoints.getLast().algorithm();
          if (!AuditChainHasher.ALGORITHM.equals(algorithm)) {
            throw new IllegalStateException(
                "audit chain of " + month + " uses the unknown algorithm " + algorithm);
          }
          Recomputation recomputed = recompute(month, bounds, checkpoints, seal);
          long chainedEntries =
              seal != null
                  ? seal.entryCount()
                  : checkpoints.isEmpty() ? 0 : checkpoints.getLast().position();
          Status verdict =
              recomputed.deviation() != null
                  ? Status.BROKEN
                  : checkpoints.isEmpty() && seal == null ? Status.UNCHAINED : Status.INTACT;
          return new AuditChainReport(
              month,
              verdict,
              seal != null,
              chainedEntries,
              Math.max(0, recomputed.count() - chainedEntries),
              checkpoints.size(),
              checkpoints.isEmpty()
                  ? seal == null ? null : AuditChainHasher.hex(seal.headHash())
                  : AuditChainHasher.hex(checkpoints.getLast().headHash()),
              seal == null ? null : seal.sealEventId(),
              algorithm,
              recomputed.deviation());
        });
  }

  private Recomputation recompute(
      YearMonth month, Bounds bounds, List<Checkpoint> checkpoints, Seal seal) {
    Walk walk = new Walk(AuditChainHasher.start(month), checkpoints, bounds.start());
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
          statement.setTimestamp(1, Timestamp.from(bounds.start()));
          statement.setTimestamp(2, Timestamp.from(bounds.end()));
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
              bounds.end());
    }
    return new Recomputation(walk.count(), deviation);
  }

  /** The recomputed chain, compared against the checkpoints as the entries stream past. */
  private static final class Walk {
    private final List<Checkpoint> checkpoints;
    private byte[] hash;
    private long count;
    private int next;
    private Instant segmentFrom;
    private Deviation deviation;

    Walk(byte[] start, List<Checkpoint> checkpoints, Instant monthStart) {
      this.hash = start;
      this.checkpoints = checkpoints;
      this.segmentFrom = monthStart;
    }

    void accept(ChainedEntry entry) {
      count++;
      hash = AuditChainHasher.next(hash, entry);
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

  private List<LocalDate> unsealedMonthsBelow(Instant settled) {
    return jdbc.query(
        UNSEALED_MONTHS_BELOW,
        (rs, row) -> rs.getObject("month", LocalDate.class),
        Timestamp.from(settled));
  }

  /** The bounds of the partition of {@code first}, in the time zone of the database session. */
  private Bounds bounds(LocalDate first) {
    return jdbc.queryForObject(
        "SELECT ?::date::timestamptz AS month_start,"
            + " (?::date + interval '1 month')::timestamptz AS month_end",
        (rs, row) ->
            new Bounds(
                rs.getTimestamp("month_start").toInstant(),
                rs.getTimestamp("month_end").toInstant()),
        Date.valueOf(first),
        Date.valueOf(first));
  }

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
   * Seals {@code month} with {@code head} and records the {@code AUDIT_LOG_MONTH_SEALED} entry in
   * the same transaction; {@code false} when the month is sealed already.
   */
  private boolean seal(YearMonth month, LocalDate first, Head head) {
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
                      Date.valueOf(first),
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
  // Checkpoints and seals
  // ---------------------------------------------------------------------------------------------

  private java.util.Optional<Head> head(LocalDate first) {
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

  private java.util.Optional<Seal> seal(LocalDate first) {
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

  /** Seconds since the oldest entry above any chain head was recorded, {@code 0} for none. */
  double lagSeconds() {
    try {
      Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
      Instant oldest = null;
      for (LocalDate first : unsealedMonthsBelow(now)) {
        Bounds bounds = bounds(first);
        Head head = head(first).orElseGet(() -> Head.start(YearMonth.from(first)));
        Timestamp candidate =
            head.position() == 0
                ? jdbc.queryForObject(
                    "SELECT min(recorded_at) " + ENTRIES_FROM,
                    Timestamp.class,
                    Timestamp.from(bounds.start()),
                    Timestamp.from(bounds.end()))
                : jdbc.queryForObject(
                    "SELECT min(recorded_at) " + ENTRIES_FROM + AFTER_KEY,
                    Timestamp.class,
                    Timestamp.from(head.lastRecordedAt()),
                    Timestamp.from(bounds.end()),
                    Timestamp.from(head.lastRecordedAt()),
                    head.lastEventId());
        if (candidate != null && (oldest == null || candidate.toInstant().isBefore(oldest))) {
          oldest = candidate.toInstant();
        }
      }
      return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds());
    } catch (RuntimeException e) {
      log.debug("Audit chain lag query failed: {}", e.getMessage());
      return Double.NaN;
    }
  }

  double endedUnsealedMonths() {
    try {
      Long count = jdbc.queryForObject(ENDED_UNSEALED_MONTHS, Long.class);
      return count == null ? Double.NaN : count;
    } catch (RuntimeException e) {
      log.debug("Audit chain seal query failed: {}", e.getMessage());
      return Double.NaN;
    }
  }

  private void gauge(
      MeterRegistry meterRegistry,
      String name,
      String unit,
      String description,
      ToDoubleFunction<AuditChainService> value) {
    Gauge.builder(name, this, value)
        .baseUnit(unit)
        .description(description)
        .register(meterRegistry);
  }

  // ---------------------------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------------------------

  /** What one run did. */
  record Run(long chainedEntries, List<YearMonth> sealedMonths) {}

  /** The start and end of a monthly partition as instants. */
  private record Bounds(Instant start, Instant end) {}

  /** The chain head of a month: the position and key of the last chained entry and its hash. */
  private record Head(
      long position, Instant lastRecordedAt, UUID lastEventId, byte[] hash, String algorithm) {

    static Head start(YearMonth month) {
      return new Head(0, null, null, AuditChainHasher.start(month), AuditChainHasher.ALGORITHM);
    }

    Head extend(List<ChainedEntry> entries) {
      byte[] next = hash;
      for (ChainedEntry entry : entries) {
        next = AuditChainHasher.next(next, entry);
      }
      ChainedEntry last = entries.getLast();
      return new Head(
          position + entries.size(), last.recordedAt(), last.eventId(), next, algorithm);
    }
  }

  private record Checkpoint(
      long position, Instant lastRecordedAt, UUID lastEventId, byte[] headHash, String algorithm) {}

  private record Seal(long entryCount, byte[] headHash, String algorithm, UUID sealEventId) {}

  private record Recomputation(long count, Deviation deviation) {}
}
