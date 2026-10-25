package io.opaa.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.api.types.AuditObjectType;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sends the audit log, in the order it was recorded, to the SIEM (ADR-0055). The position of the
 * last sent entry lives in {@code siem_export_cursor}; it moves only after a delivery succeeded, so
 * an unreachable receiver loses nothing and gets the same entries again (Entscheidung 5). An entry
 * goes out only once every client transaction that began before it has ended; that limit is read in
 * a statement of its own before the entries. The export starts at the moment it is first switched
 * on, and again after it was switched off; that pause and entries the retention removed before they
 * were sent are reported as gaps (Entscheidung 6). The run itself is guarded by the scheduler lock
 * of {@link SiemExportSchedule}; the conditional cursor update keeps a second run from moving the
 * position twice (ADR-0053).
 *
 * <p>The one reader of {@code audit_log} besides the revision's access paths and the
 * self-disclosure (Entscheidung 3). Its query never selects a pseudonym, a reason or a payload and
 * masks in SQL the id of an object that belongs to one person; {@link PersonalAuditObjects} masks
 * what the label alone does not tell.
 */
class SiemExportService {

  private static final Logger log = LoggerFactory.getLogger(SiemExportService.class);

  /** Batches one run sends at most before it yields to the next tick. */
  static final int MAX_BATCHES_PER_RUN = 20;

  /** The longest wait between two attempts while the receiver keeps failing. */
  static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

  /** How far behind the limit may fall before the log warns, and how often it repeats. */
  static final Duration STUCK_AFTER = Duration.ofMinutes(10);

  private static final String PERSONAL_OBJECT =
      "(object_type IN ('USER_ACCOUNT', 'API_TOKEN') OR object_label IN (?, ?, ?))";

  private static final Set<AuditObjectType> OWNED_TYPES =
      Set.of(
          AuditObjectType.SPACE,
          AuditObjectType.KNOWLEDGE_LIBRARY,
          AuditObjectType.PROMPT_LIBRARY,
          AuditObjectType.PROMPT);

  private final JdbcTemplate jdbc;
  private final SiemExportProperties properties;
  private final SyslogSender sender;
  private final CefSyslogFormatter formatter;
  private final List<PersonalAuditObjects> personalObjects;
  private final Counter sent;
  private final Counter failed;
  private final Counter gaps;

  /**
   * Age in seconds of the oldest entry not yet sent, {@code 0} with nothing due, as this instance
   * last saw it: it does not grow in a quiet phase, only while entries wait.
   */
  private final AtomicLong lagSeconds = new AtomicLong();

  /** Process-local backoff and warning throttle of the instance holding the lock (ADR-0053). */
  private int consecutiveFailures;

  private Instant nextAttempt = Instant.EPOCH;
  private Instant lastStuckWarning = Instant.EPOCH;

  SiemExportService(
      JdbcTemplate jdbc,
      SiemExportProperties properties,
      SyslogSender sender,
      CefSyslogFormatter formatter,
      List<PersonalAuditObjects> personalObjects,
      MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.sender = sender;
    this.formatter = formatter;
    this.personalObjects = List.copyOf(personalObjects);
    this.sent = counter(meterRegistry, "sent", "Audit log entries delivered to the SIEM");
    this.failed = counter(meterRegistry, "failed", "Deliveries to the SIEM that failed");
    this.gaps = counter(meterRegistry, "gap", "Gaps reported to the SIEM");
    Gauge.builder("opaa.siem.export.lag", lagSeconds, AtomicLong::get)
        .baseUnit("seconds")
        .description("Age of the oldest audit log entry not yet delivered to the SIEM")
        .register(meterRegistry);
  }

  /** One run: as many batches as are due, up to {@link #MAX_BATCHES_PER_RUN}. */
  void run() {
    Cursor cursor = cursor();
    if (Instant.now().isBefore(nextAttempt)) {
      return;
    }
    for (int batch = 0; batch < MAX_BATCHES_PER_RUN && cursor != null; batch++) {
      cursor = deliverNext(cursor);
    }
  }

  /**
   * Delivers the next batch after {@code cursor}, with a gap note first where the export was off or
   * the retention removed entries behind it.
   *
   * @return the new position when a full batch went out and more may be due, otherwise {@code null}
   */
  Cursor deliverNext(Cursor cursor) {
    Limit limit = limit();
    Instant cutoffBefore = lastCutoff();
    lagSeconds.set(oldestPendingAge(cursor, limit.now()));
    warnIfStuck(limit);
    List<String> messages = new ArrayList<>();
    Cursor position = cursor;
    String gapReason = null;
    if (cursor.disabledAt() != null) {
      messages.add(formatter.formatGap(cursor.recordedAt(), limit.now(), limit.now(), "disabled"));
      position = new Cursor(limit.now(), null, null);
      gapReason = "disabled";
    } else if (cutoffBefore != null && cursor.recordedAt().isBefore(cutoffBefore)) {
      messages.add(
          formatter.formatGap(cursor.recordedAt(), cutoffBefore, limit.now(), "retention"));
      position = new Cursor(cutoffBefore, null, null);
      gapReason = "retention";
    }
    List<AuditExportEntry> entries =
        gapReason == null ? withoutPersonalObjects(due(position, limit.settled())) : List.of();
    if (!Objects.equals(cutoffBefore, lastCutoff())) {
      // the retention ran meanwhile: the next run decides on the new limit
      return null;
    }
    for (AuditExportEntry entry : entries) {
      messages.add(formatter.format(entry));
    }
    if (messages.isEmpty()) {
      return null;
    }
    try {
      sender.send(messages);
    } catch (IOException | RuntimeException e) {
      recordFailure(entries.size(), e);
      return null;
    }
    recordSuccess();
    Cursor next =
        entries.isEmpty()
            ? position
            : new Cursor(entries.getLast().recordedAt(), entries.getLast().eventId(), null);
    if (!advance(cursor, next)) {
      log.warn("SIEM export cursor moved meanwhile; this run stops");
      return null;
    }
    sent.increment(entries.size());
    lagSeconds.set(oldestPendingAge(next, limit.now()));
    if (gapReason != null) {
      gaps.increment();
      log.warn("SIEM export reported a gap ({}) from {}", gapReason, cursor.recordedAt());
      return position;
    }
    return entries.size() == properties.batchSize() ? next : null;
  }

  /** The position, created at the database clock's now when the export runs for the first time. */
  Cursor cursor() {
    jdbc.update(
        "INSERT INTO siem_export_cursor (id, started_at, last_recorded_at, last_event_id,"
            + " updated_at) VALUES (1, clock_timestamp(), clock_timestamp(), NULL,"
            + " clock_timestamp()) ON CONFLICT (id) DO NOTHING");
    return jdbc.queryForObject(
        "SELECT last_recorded_at, last_event_id, disabled_at FROM siem_export_cursor WHERE id = 1",
        (rs, row) -> {
          Timestamp disabledAt = rs.getTimestamp("disabled_at");
          return new Cursor(
              rs.getTimestamp("last_recorded_at").toInstant(),
              rs.getObject("last_event_id", UUID.class),
              disabledAt == null ? null : disabledAt.toInstant());
        });
  }

  /**
   * Marks that an instance started with the export switched off: the next run with it switched on
   * begins anew and reports the pause. Nothing to mark before the export ever ran.
   */
  static void markDisabled(JdbcTemplate jdbc) {
    jdbc.update(
        "UPDATE siem_export_cursor SET disabled_at = coalesce(disabled_at, clock_timestamp())"
            + " WHERE id = 1");
  }

  /**
   * The database clock and the limit below which every entry is settled: the start of the oldest
   * transaction of another client session, at most now minus {@code settle-delay}. {@code
   * recorded_at} is stamped inside its transaction, so an entry not yet visible never lies below
   * its transaction's start; read before the entries, everything below is visible to their read.
   */
  private Limit limit() {
    return jdbc.queryForObject(
        "SELECT clock_timestamp() AS now, LEAST(clock_timestamp() - (? * interval '1 millisecond'),"
            + " coalesce(oldest.xact_start, clock_timestamp())) AS settled, oldest.pid,"
            + " oldest.application_name FROM (SELECT 1) AS one LEFT JOIN LATERAL"
            + " (SELECT xact_start, pid, application_name FROM pg_stat_activity"
            + " WHERE datname = current_database() AND pid <> pg_backend_pid()"
            + " AND backend_type = 'client backend' AND xact_start IS NOT NULL"
            + " ORDER BY xact_start LIMIT 1) AS oldest ON true",
        (rs, row) ->
            new Limit(
                rs.getTimestamp("now").toInstant().truncatedTo(ChronoUnit.MICROS),
                rs.getTimestamp("settled").toInstant(),
                rs.getObject("pid", Integer.class),
                rs.getString("application_name")),
        properties.settleDelay().toMillis());
  }

  private List<AuditExportEntry> due(Cursor position, Instant settled) {
    UUID after = position.eventId() == null ? new UUID(0, 0) : position.eventId();
    Timestamp from = Timestamp.from(position.recordedAt());
    return jdbc.query(
        "SELECT event_id, recorded_at, organization_id, event_type, object_type,"
            + " CASE WHEN "
            + PERSONAL_OBJECT
            + " THEN NULL ELSE object_id END AS object_ref,"
            + " CASE WHEN ? AND NOT "
            + PERSONAL_OBJECT
            + " THEN object_label END AS object_name,"
            + " outcome, schema_version FROM audit_log"
            + " WHERE recorded_at >= ? AND recorded_at < ? AND (recorded_at, event_id) > (?, ?)"
            + " ORDER BY recorded_at, event_id LIMIT ?",
        (rs, row) ->
            new AuditExportEntry(
                rs.getObject("event_id", UUID.class),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("organization_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("object_type"),
                rs.getString("object_ref"),
                rs.getString("object_name"),
                rs.getString("outcome"),
                rs.getObject("schema_version", Short.class)),
        AuditNeutralLabels.PRIVATE_ASSET,
        AuditNeutralLabels.PERSONAL_SPACE,
        AuditNeutralLabels.PRIVATE_PROMPT,
        properties.includeLabels(),
        AuditNeutralLabels.PRIVATE_ASSET,
        AuditNeutralLabels.PERSONAL_SPACE,
        AuditNeutralLabels.PRIVATE_PROMPT,
        from,
        Timestamp.from(settled),
        from,
        after,
        properties.batchSize());
  }

  /**
   * {@code entries} with id and name left out where the owning module says the object belongs to
   * one person - the safeguard for an entry whose writer did not use the neutral label.
   */
  private List<AuditExportEntry> withoutPersonalObjects(List<AuditExportEntry> entries) {
    Map<AuditObjectType, Set<String>> idsByType = new HashMap<>();
    for (AuditExportEntry entry : entries) {
      AuditObjectType type = AuditObjectType.valueOf(entry.objectType());
      if (entry.objectId() != null && OWNED_TYPES.contains(type)) {
        idsByType.computeIfAbsent(type, any -> new HashSet<>()).add(entry.objectId());
      }
    }
    Map<AuditObjectType, Set<String>> personal = new HashMap<>();
    idsByType.forEach(
        (type, ids) -> {
          for (PersonalAuditObjects port : personalObjects) {
            personal
                .computeIfAbsent(type, any -> new HashSet<>())
                .addAll(port.personal(type, ids).keySet());
          }
        });
    List<AuditExportEntry> masked = new ArrayList<>(entries.size());
    for (AuditExportEntry entry : entries) {
      boolean isPersonal =
          entry.objectId() != null
              && personal
                  .getOrDefault(AuditObjectType.valueOf(entry.objectType()), Set.of())
                  .contains(entry.objectId());
      masked.add(
          isPersonal
              ? new AuditExportEntry(
                  entry.eventId(),
                  entry.recordedAt(),
                  entry.organizationId(),
                  entry.eventType(),
                  entry.objectType(),
                  null,
                  null,
                  entry.outcome(),
                  entry.schemaVersion())
              : entry);
    }
    return masked;
  }

  /** Seconds since the oldest entry after {@code position} was recorded, {@code 0} for none. */
  private long oldestPendingAge(Cursor position, Instant now) {
    Timestamp from = Timestamp.from(position.recordedAt());
    Timestamp oldest =
        jdbc.queryForObject(
            "SELECT min(recorded_at) FROM audit_log WHERE recorded_at >= ?"
                + " AND (recorded_at, event_id) > (?, ?)",
            Timestamp.class,
            from,
            from,
            position.eventId() == null ? new UUID(0, 0) : position.eventId());
    return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds());
  }

  /** A limit far behind now means an open transaction holds the export back. */
  private void warnIfStuck(Limit limit) {
    Duration behind = Duration.between(limit.settled(), limit.now());
    if (behind.compareTo(STUCK_AFTER) < 0
        || Duration.between(lastStuckWarning, limit.now()).compareTo(STUCK_AFTER) < 0) {
      return;
    }
    lastStuckWarning = limit.now();
    log.warn(
        "SIEM export held back for {} min by the open transaction of database session {} ({});"
            + " idle_in_transaction_session_timeout ends such sessions",
        behind.toMinutes(),
        limit.oldestPid(),
        limit.oldestApplication());
  }

  private Instant lastCutoff() {
    Timestamp cutoff =
        jdbc.queryForObject(
            "SELECT max(last_cutoff) FROM audit_retention_settings", Timestamp.class);
    return cutoff == null ? null : cutoff.toInstant();
  }

  private boolean advance(Cursor from, Cursor to) {
    return jdbc.update(
            "UPDATE siem_export_cursor SET last_recorded_at = ?, last_event_id = ?,"
                + " disabled_at = NULL, updated_at = clock_timestamp() WHERE id = 1"
                + " AND last_recorded_at = ? AND last_event_id IS NOT DISTINCT FROM ?",
            Timestamp.from(to.recordedAt()),
            to.eventId(),
            Timestamp.from(from.recordedAt()),
            from.eventId())
        == 1;
  }

  /** Waits twice as long after each failure, up to {@link #MAX_BACKOFF}; warns at the first. */
  private void recordFailure(int entries, Exception e) {
    failed.increment();
    consecutiveFailures++;
    long seconds = Math.min(MAX_BACKOFF.toSeconds(), 30L << Math.min(consecutiveFailures, 10));
    nextAttempt = Instant.now().plusSeconds(seconds);
    if (consecutiveFailures == 1 || seconds == MAX_BACKOFF.toSeconds()) {
      log.warn(
          "SIEM export to {}:{} failed ({} times in a row), {} entries stay due, next attempt in"
              + " {}s: {}",
          properties.host(),
          properties.port(),
          consecutiveFailures,
          entries,
          seconds,
          e.getMessage());
    }
  }

  private void recordSuccess() {
    if (consecutiveFailures > 0) {
      log.info("SIEM export delivers again; {} attempts had failed", consecutiveFailures);
    }
    consecutiveFailures = 0;
    nextAttempt = Instant.EPOCH;
  }

  private static Counter counter(MeterRegistry registry, String result, String description) {
    return Counter.builder("opaa.siem.export")
        .tag("result", result)
        .description(description)
        .register(registry);
  }

  /**
   * The last sent entry; {@code eventId} is {@code null} at the start and after a gap, {@code
   * disabledAt} set once an instance started with the export switched off.
   */
  record Cursor(Instant recordedAt, UUID eventId, Instant disabledAt) {}

  /** The database clock, the settled limit and the session of the oldest open transaction. */
  private record Limit(Instant now, Instant settled, Integer oldestPid, String oldestApplication) {}
}
