package io.opaa.connection.log;

import static io.opaa.common.SelfDisclosureSectionReader.instant;
import static io.opaa.common.SelfDisclosureSectionReader.keyed;

import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.common.SelfDisclosureSectionReader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code connection-log} of the self-disclosure: the entries of the connection log in
 * which the person acted or whose connection they concern, found through her existing pseudonym
 * (none is minted). Pseudonyms never go out: the actor is "Sie", "andere Person" or "System". The
 * account name of a library goes out only for a connection she established herself.
 */
@Component
public class ConnectionLogSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "connection-log",
          "Verbindungsprotokoll",
          List.of(
              new Section(
                  "entries",
                  "Einträge zu Ihren Verbindungen und Handlungen",
                  List.of(
                      Field.of("recordedAt", "Zeitpunkt", "connection_log.recorded_at"),
                      Field.of("eventType", "Ereignis", "connection_log.event_type"),
                      Field.derived("actor", "Handelnd", "connection_log.actor_ref"),
                      Field.of("ownerKind", "Verbindung von", "connection_log.owner_kind"),
                      Field.derived("person", "Betroffene Person", "connection_log.person_ref"),
                      Field.of("library", "Bibliothek", "connection_log.library_id", "assets.name"),
                      Field.of(
                          "accountLabel", "Konto beim Anbieter", "connection_log.account_label"),
                      Field.of("profile", "Zugang", "connection_log.profile_name"),
                      Field.of("cause", "Anlass", "connection_log.cause")))));

  private static final List<String> STARTS = List.of("CONNECTED", "RECONNECTED");

  private final JdbcTemplate jdbc;
  private final AuditActorPseudonymService pseudonyms;
  private final SelfDisclosureSectionReader reader;

  public ConnectionLogSelfDisclosureExporter(
      JdbcTemplate jdbc,
      AuditActorPseudonymService pseudonyms,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.pseudonyms = pseudonyms;
    this.reader = new SelfDisclosureSectionReader(transactionManager);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    Optional<UUID> pseudonym = pseudonyms.findExistingPseudonym(subject.accountId());
    if (pseudonym.isEmpty()) {
      return;
    }
    String mine = pseudonym.get().toString();
    reader.copy(
        sink,
        "entries",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT l.event_id, l.recorded_at, l.event_type, l.actor_ref, l.owner_kind,"
                    + " l.person_ref, a.name AS library_name, l.account_label, l.profile_name,"
                    + " l.cause FROM connection_log l LEFT JOIN assets a ON a.id = l.library_id"
                    + " WHERE l.organization_id = ? AND (l.actor_ref = ? OR l.person_ref = ?)"
                    + " AND (l.recorded_at, l.event_id) > (?, ?)"
                    + " ORDER BY l.recorded_at, l.event_id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "recorded_at", "event_id");
                  String actor = rs.getString("actor_ref");
                  String person = rs.getString("person_ref");
                  String eventType = rs.getString("event_type");
                  boolean ownStart = mine.equals(actor) && STARTS.contains(eventType);
                  record.put("recordedAt", instant(rs.getTimestamp("recorded_at")));
                  record.put("eventType", eventType);
                  record.put("actor", label(actor, mine));
                  record.put("ownerKind", rs.getString("owner_kind"));
                  record.put("person", person == null ? null : label(person, mine));
                  record.put("library", rs.getString("library_name"));
                  record.put("accountLabel", ownStart ? rs.getString("account_label") : null);
                  record.put("profile", rs.getString("profile_name"));
                  record.put("cause", rs.getString("cause"));
                  return record;
                },
                subject.organizationId(),
                mine,
                mine,
                afterTime,
                afterId,
                limit));
  }

  private static String label(String ref, String mine) {
    if (mine.equals(ref)) {
      return SelfDisclosureLabels.YOU;
    }
    return ConnectionLogActor.SYSTEM_LABEL.equals(ref)
        ? SelfDisclosureLabels.SYSTEM
        : SelfDisclosureLabels.OTHER_PERSON;
  }
}
