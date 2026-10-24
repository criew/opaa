package io.opaa.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sends the audit log, in the order it was recorded, to the SIEM (ADR-0055). The position of the
 * last sent entry lives in {@code siem_export_cursor}; it moves only after a delivery succeeded, so
 * an unreachable receiver loses nothing and gets the same entries again (Entscheidung 5). The
 * export starts at the moment it is first switched on; entries the retention removed before they
 * were sent are reported as a gap (Entscheidung 6). The run itself is guarded by the scheduler lock
 * of {@link SiemExportSchedule}; the conditional cursor update keeps a second run from moving the
 * position twice (ADR-0053).
 *
 * <p>This is the one reader of {@code audit_log} besides the revision's access paths and the
 * self-disclosure (Entscheidung 3): it selects the columns it sends and never a pseudonym.
 */
class SiemExportService {

  private static final Logger log = LoggerFactory.getLogger(SiemExportService.class);

  /** The neutral label of an owner-only asset, {@code Asset#PRIVATE_AUDIT_NAME}. */
  static final String PRIVATE_OBJECT_LABEL = "Private Bibliothek";

  /** Object kinds whose id is a person's pseudonym or bound to one person. */
  private static final Set<String> PERSONAL_OBJECT_TYPES = Set.of("USER_ACCOUNT", "API_TOKEN");

  /** Batches one run sends at most before it yields to the next tick. */
  static final int MAX_BATCHES_PER_RUN = 20;

  private final JdbcTemplate jdbc;
  private final SiemExportProperties properties;
  private final SyslogSender sender;
  private final CefSyslogFormatter formatter;
  private final AuditRetentionSettingsRepository retention;
  private final Counter sent;
  private final Counter failed;
  private final Counter gaps;

  SiemExportService(
      JdbcTemplate jdbc,
      SiemExportProperties properties,
      SyslogSender sender,
      CefSyslogFormatter formatter,
      AuditRetentionSettingsRepository retention,
      MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.sender = sender;
    this.formatter = formatter;
    this.retention = retention;
    this.sent = counter(meterRegistry, "sent", "Audit log entries delivered to the SIEM");
    this.failed = counter(meterRegistry, "failed", "Deliveries to the SIEM that failed");
    this.gaps = counter(meterRegistry, "gap", "Gaps of removed entries reported to the SIEM");
  }

  /** One run: as many batches as are due, up to {@link #MAX_BATCHES_PER_RUN}. */
  void run() {
    Cursor cursor = cursor();
    for (int batch = 0; batch < MAX_BATCHES_PER_RUN && cursor != null; batch++) {
      cursor = deliverNext(cursor);
    }
  }

  /**
   * Delivers the next batch after {@code cursor}, with a gap note first where the retention removed
   * entries behind it.
   *
   * @return the new position when a full batch went out and more may be due, otherwise {@code null}
   */
  Cursor deliverNext(Cursor cursor) {
    List<String> messages = new ArrayList<>();
    Cursor position = cursor;
    Instant cutoff =
        retention.findSingleton().map(AuditRetentionSettings::getLastCutoff).orElse(null);
    boolean gap = cutoff != null && cursor.recordedAt().isBefore(cutoff);
    if (gap) {
      messages.add(formatter.formatGap(cursor.recordedAt(), cutoff, Instant.now()));
      position = new Cursor(cutoff, null);
    }
    List<AuditExportEntry> entries = due(position);
    for (AuditExportEntry entry : entries) {
      messages.add(formatter.format(entry));
    }
    if (messages.isEmpty()) {
      return null;
    }
    try {
      sender.send(messages);
    } catch (IOException | RuntimeException e) {
      failed.increment();
      log.warn(
          "SIEM export to {}:{} failed, {} entries stay due: {}",
          properties.host(),
          properties.port(),
          entries.size(),
          e.getMessage());
      return null;
    }
    Cursor next =
        entries.isEmpty()
            ? position
            : new Cursor(entries.getLast().recordedAt(), entries.getLast().eventId());
    if (!advance(cursor, next)) {
      log.warn("SIEM export cursor moved meanwhile; this run stops");
      return null;
    }
    sent.increment(entries.size());
    if (gap) {
      gaps.increment();
      log.warn(
          "SIEM export reported a gap: entries recorded before {} were removed before export",
          cutoff);
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
        "SELECT last_recorded_at, last_event_id FROM siem_export_cursor WHERE id = 1",
        (rs, row) ->
            new Cursor(
                rs.getTimestamp("last_recorded_at").toInstant(),
                rs.getObject("last_event_id", UUID.class)));
  }

  private List<AuditExportEntry> due(Cursor position) {
    UUID after = position.eventId() == null ? new UUID(0, 0) : position.eventId();
    return jdbc.query(
        "SELECT event_id, recorded_at, organization_id, event_type, object_type, object_id,"
            + " object_label, outcome FROM audit_log"
            + " WHERE (recorded_at, event_id) > (?, ?)"
            + " AND recorded_at <= clock_timestamp() - (? * interval '1 millisecond')"
            + " ORDER BY recorded_at, event_id LIMIT ?",
        (rs, row) -> {
          String objectType = rs.getString("object_type");
          String label = rs.getString("object_label");
          boolean personal =
              PERSONAL_OBJECT_TYPES.contains(objectType) || PRIVATE_OBJECT_LABEL.equals(label);
          return new AuditExportEntry(
              rs.getObject("event_id", UUID.class),
              rs.getTimestamp("recorded_at").toInstant(),
              rs.getObject("organization_id", UUID.class),
              rs.getString("event_type"),
              objectType,
              personal ? null : rs.getString("object_id"),
              personal ? null : label,
              rs.getString("outcome"));
        },
        Timestamp.from(position.recordedAt()),
        after,
        properties.settleDelay().toMillis(),
        properties.batchSize());
  }

  private boolean advance(Cursor from, Cursor to) {
    return jdbc.update(
            "UPDATE siem_export_cursor SET last_recorded_at = ?, last_event_id = ?,"
                + " updated_at = clock_timestamp() WHERE id = 1 AND last_recorded_at = ?"
                + " AND last_event_id IS NOT DISTINCT FROM ?",
            Timestamp.from(to.recordedAt()),
            to.eventId(),
            Timestamp.from(from.recordedAt()),
            from.eventId())
        == 1;
  }

  private static Counter counter(MeterRegistry registry, String result, String description) {
    return Counter.builder("opaa.siem.export")
        .tag("result", result)
        .description(description)
        .register(registry);
  }

  /** The last sent entry; {@code eventId} is {@code null} at the start and after a gap. */
  record Cursor(Instant recordedAt, UUID eventId) {}
}
