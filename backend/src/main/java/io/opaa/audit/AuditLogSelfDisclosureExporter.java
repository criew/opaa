package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code audit-log}: the entries in which the person acts, is the subject, is the
 * account object or is named as owner ({@link AuditOwnerPayload}), found through her existing
 * pseudonym (never minted here). Besides {@link AuditQueryService} the only reader of {@code
 * audit_log}, for the person herself only, and without an access entry of its own: the start and
 * end events of the self-disclosure stand for it.
 *
 * <p>No pseudonym, no {@code reason} and no other person leaves: actor, subject and account object
 * go out as "Sie", "andere Person" or "System", a label of another account is dropped, {@code
 * before}/{@code after} pass {@link AuditSelfDisclosurePayload}. Read month by month: one scan of
 * the window for the ids of the person's entries ({@code subject_ref} has no index on purpose,
 * docs/features/security-and-compliance.md, "Protokoll der Selbstauskunft"), then the rows in pages
 * of {@link #PAGE_SIZE} by primary key, each read in a short read-only transaction.
 */
@Component
public class AuditLogSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "audit-log",
          "Protokoll",
          List.of(
              new Section(
                  "entries",
                  "Protokolleinträge, in denen Sie handeln oder betroffen sind",
                  List.of(
                      Field.of("recordedAt", "Zeitpunkt", "audit_log.recorded_at"),
                      Field.of("eventType", "Ereignisart", "audit_log.event_type"),
                      Field.derived(
                          "actor", "Handelnd", "audit_log.actor_kind", "audit_log.actor_ref"),
                      Field.of("objectType", "Objektart", "audit_log.object_type"),
                      Field.derived(
                          "objectAccount",
                          "Konto als Objekt",
                          "audit_log.object_type",
                          "audit_log.object_id"),
                      Field.of("objectLabel", "Objektbezeichnung", "audit_log.object_label"),
                      Field.of("subjectKind", "Art der Betroffenen", "audit_log.subject_kind"),
                      Field.derived(
                          "subject",
                          "Betroffen",
                          "audit_log.subject_kind",
                          "audit_log.subject_ref"),
                      Field.of("before", "Vorher", "audit_log.before"),
                      Field.of("after", "Nachher", "audit_log.after"),
                      Field.of("outcome", "Ergebnis", "audit_log.outcome"),
                      Field.of("correlationRef", "Vorgangskennung", "audit_log.correlation_ref"),
                      Field.derived("reasonGiven", "Anlass angegeben", "audit_log.reason")))));

  static final int PAGE_SIZE = 500;

  private static final String USER = "USER";
  private static final String USER_ACCOUNT = "USER_ACCOUNT";
  private static final String[] OWNER_EVENT_TYPES =
      AuditOwnerPayload.EVENT_TYPES.stream().map(Enum::name).sorted().toArray(String[]::new);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  AuditLogSelfDisclosureExporter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    Span span = readOnly.execute(status -> span(subject));
    if (span == null) {
      return;
    }
    Person person =
        new Person(subject.organizationId(), span.pseudonym(), subject.accountId().toString());
    RecordWriter writer = null;
    Instant month = monthOf(span.first());
    while (month.isBefore(span.end())) {
      Instant next = ZonedDateTime.ofInstant(month, ZoneOffset.UTC).plusMonths(1).toInstant();
      Window window = new Window(month, next.isBefore(span.end()) ? next : span.end());
      List<UUID> keys = readOnly.execute(status -> keys(person, window));
      for (int from = 0; keys != null && from < keys.size(); from += PAGE_SIZE) {
        UUID[] page =
            keys.subList(from, Math.min(from + PAGE_SIZE, keys.size())).toArray(UUID[]::new);
        List<Map<String, Object>> rows = readOnly.execute(status -> rows(person, window, page));
        if (rows == null || rows.isEmpty()) {
          continue;
        }
        if (writer == null) {
          writer = sink.section("entries");
        }
        for (Map<String, Object> row : rows) {
          writer.write(row);
        }
      }
      month = next;
    }
  }

  /** The person's pseudonym and the time span of the log to read, or {@code null}. */
  private Span span(SelfDisclosureSubject subject) {
    List<String> pseudonyms =
        jdbc.queryForList(
            "SELECT CAST(pseudonym_id AS text) FROM audit_actor_pseudonyms"
                + " WHERE user_id = ? AND organization_id = ?",
            String.class,
            subject.accountId(),
            subject.organizationId());
    if (pseudonyms.isEmpty()) {
      return null;
    }
    return jdbc.queryForObject(
        "SELECT min(recorded_at) AS first, clock_timestamp() AS now FROM audit_log"
            + " WHERE organization_id = ?",
        (rs, i) -> {
          Timestamp first = rs.getTimestamp("first");
          return first == null
              ? null
              : new Span(
                  pseudonyms.getFirst(),
                  first.toInstant(),
                  rs.getTimestamp("now").toInstant().plusNanos(1000));
        },
        subject.organizationId());
  }

  /**
   * The ids of the person's entries in one window, in order: the one scan of the window's
   * partition. Besides actor, subject and account object, an entry of an event type with an owner
   * names the person under {@link AuditOwnerPayload#KEY}. Only the ids are held; the rows follow
   * page by page through the primary key.
   */
  private List<UUID> keys(Person person, Window window) {
    return jdbc.query(
        "SELECT event_id FROM audit_log"
            + " WHERE organization_id = ? AND recorded_at >= ? AND recorded_at < ?"
            + " AND ((actor_kind = 'USER' AND actor_ref = ?)"
            + " OR (subject_kind = 'USER' AND subject_ref = ?)"
            + " OR (object_type = 'USER_ACCOUNT' AND object_id = ?)"
            + " OR (event_type = ANY (?) AND (before LIKE ? OR after LIKE ?"
            + " OR before LIKE ? OR after LIKE ?)))"
            // no writer records a revision read with a subject or account object today; should
            // one, a read about the person by someone else belongs to an anlassbezogene Klärung
            + " AND (event_type <> 'AUDIT_LOG_ACCESSED' OR (actor_kind = 'USER' AND actor_ref = ?))"
            + " ORDER BY recorded_at, event_id",
        (rs, i) -> rs.getObject("event_id", UUID.class),
        person.organizationId(),
        Timestamp.from(window.from()),
        Timestamp.from(window.to()),
        person.pseudonym(),
        person.pseudonym(),
        person.pseudonym(),
        OWNER_EVENT_TYPES,
        AuditOwnerPayload.ownerPattern(person.pseudonym()),
        AuditOwnerPayload.ownerPattern(person.pseudonym()),
        AuditOwnerPayload.ownerPattern(person.accountId()),
        AuditOwnerPayload.ownerPattern(person.accountId()),
        person.pseudonym());
  }

  private List<Map<String, Object>> rows(Person person, Window window, UUID[] eventIds) {
    return jdbc.query(
        "SELECT event_id, recorded_at, actor_kind, actor_ref, event_type, object_type, object_id,"
            + " object_label, subject_kind, subject_ref, before, after, outcome, correlation_ref,"
            + " reason IS NOT NULL AND btrim(reason) <> '' AS reason_given"
            + " FROM audit_log"
            + " WHERE organization_id = ? AND recorded_at >= ? AND recorded_at < ?"
            + " AND event_id = ANY (?) ORDER BY recorded_at, event_id",
        (rs, i) -> record(rs, person),
        person.organizationId(),
        Timestamp.from(window.from()),
        Timestamp.from(window.to()),
        eventIds);
  }

  private static Map<String, Object> record(ResultSet rs, Person person) throws SQLException {
    Instant recordedAt = rs.getTimestamp("recorded_at").toInstant();
    AuditEventType type = eventType(rs.getString("event_type"));
    String objectType = rs.getString("object_type");
    boolean accountObject = USER_ACCOUNT.equals(objectType);
    boolean ownAccount = accountObject && person.isSelf(rs.getString("object_id"));
    String subjectKind = rs.getString("subject_kind");

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("recordedAt", recordedAt);
    record.put("eventType", rs.getString("event_type"));
    record.put(
        "actor",
        USER.equals(rs.getString("actor_kind"))
            ? person.label(rs.getString("actor_ref"))
            : SelfDisclosureLabels.SYSTEM);
    record.put("objectType", objectType);
    record.put(
        "objectAccount",
        accountObject
            ? (ownAccount ? SelfDisclosureLabels.YOU : SelfDisclosureLabels.OTHER_PERSON)
            : null);
    record.put("objectLabel", accountObject && !ownAccount ? null : rs.getString("object_label"));
    record.put("subjectKind", subjectKind);
    record.put(
        "subject", USER.equals(subjectKind) ? person.label(rs.getString("subject_ref")) : null);
    record.put(
        "before",
        AuditSelfDisclosurePayload.disclose(
            type, rs.getString("before"), person.pseudonym(), person.accountId()));
    record.put(
        "after",
        AuditSelfDisclosurePayload.disclose(
            type, rs.getString("after"), person.pseudonym(), person.accountId()));
    record.put("outcome", rs.getString("outcome"));
    record.put("correlationRef", rs.getString("correlation_ref"));
    record.put("reasonGiven", rs.getBoolean("reason_given"));
    return record;
  }

  private static AuditEventType eventType(String name) {
    try {
      return AuditEventType.valueOf(name);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  private static Instant monthOf(Instant instant) {
    return ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)
        .withDayOfMonth(1)
        .truncatedTo(ChronoUnit.DAYS)
        .toInstant();
  }

  private record Span(String pseudonym, Instant first, Instant end) {}

  private record Window(Instant from, Instant to) {}

  /** The person as the log names her: by pseudonym, or by account id in a few account objects. */
  private record Person(UUID organizationId, String pseudonym, String accountId) {

    boolean isSelf(String ref) {
      return pseudonym.equals(ref) || accountId.equals(ref);
    }

    String label(String ref) {
      return isSelf(ref) ? SelfDisclosureLabels.YOU : SelfDisclosureLabels.OTHER_PERSON;
    }
  }
}
