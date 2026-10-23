package io.opaa.diagnosticaccess;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code diagnostic-context-log} of the self-disclosure: the person's own search
 * diagnoses (time, target kind, test question, hit count), then the diagnoses run in her rights
 * context by someone else (time, {@link SelfDisclosureLabels#OTHER_PERSON}, hit count). Neither
 * section carries a justification, a pseudonym, the permission snapshot or the hits; a diagnosis in
 * her context carries no test question, the free text of a third person. Both are found through her
 * pseudonym; read in pages, each in a short read-only transaction.
 */
@Component
public class DiagnosticContextLogSelfDisclosureExporter implements SelfDisclosureExporter {

  private static final String TABLE = "diagnostic_context_log.";

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "diagnostic-context-log",
          "Diagnoseprotokoll",
          List.of(
              new Section(
                  "own",
                  "Ihre Suchdiagnosen",
                  List.of(
                      Field.of("recordedAt", "Zeitpunkt", TABLE + "recorded_at"),
                      Field.of("targetKind", "Zielart", TABLE + "target_kind"),
                      Field.of("testQuestion", "Testfrage", TABLE + "test_question"),
                      Field.of("hitCount", "Treffer", TABLE + "hit_count"))),
              new Section(
                  "inYourContext",
                  "Suchdiagnosen in Ihrem Rechtekontext",
                  List.of(
                      Field.of("recordedAt", "Zeitpunkt", TABLE + "recorded_at"),
                      Field.derived("actor", "Ausgeführt von", TABLE + "actor_ref"),
                      Field.of("hitCount", "Treffer", TABLE + "hit_count")))));

  static final int PAGE_SIZE = 500;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public DiagnosticContextLogSelfDisclosureExporter(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
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
    List<UUID> pseudonyms =
        readOnly.execute(
            s ->
                jdbc.queryForList(
                    "SELECT pseudonym_id FROM audit_actor_pseudonyms WHERE user_id = ?"
                        + " AND organization_id = ?",
                    UUID.class,
                    subject.accountId(),
                    subject.organizationId()));
    if (pseudonyms == null || pseudonyms.isEmpty()) {
      return;
    }
    String pseudonym = pseudonyms.getFirst().toString();
    writePaged(
        sink,
        "own",
        "SELECT event_id, recorded_at, target_kind, test_question, hit_count"
            + " FROM diagnostic_context_log WHERE organization_id = ? AND actor_ref = ?",
        subject,
        pseudonym,
        (rs, record) -> {
          record.put("targetKind", targetKind(rs.getString("target_kind")));
          record.put("testQuestion", rs.getString("test_question"));
          record.put("hitCount", rs.getInt("hit_count"));
        });
    writePaged(
        sink,
        "inYourContext",
        "SELECT event_id, recorded_at, actor_ref, hit_count FROM diagnostic_context_log"
            + " WHERE organization_id = ? AND target_kind = 'USER' AND target_ref = ?",
        subject,
        pseudonym,
        (rs, record) -> {
          record.put(
              "actor",
              pseudonym.equals(rs.getString("actor_ref"))
                  ? SelfDisclosureLabels.YOU
                  : SelfDisclosureLabels.OTHER_PERSON);
          record.put("hitCount", rs.getInt("hit_count"));
        });
  }

  private void writePaged(
      SelfDisclosureSink sink,
      String section,
      String select,
      SelfDisclosureSubject subject,
      String pseudonym,
      RecordFiller filler)
      throws IOException {
    RecordWriter writer = null;
    Instant afterRecorded = Instant.EPOCH.minusSeconds(1);
    UUID afterId = new UUID(0, 0);
    while (true) {
      Instant cursorTime = afterRecorded;
      UUID cursorId = afterId;
      List<Map<String, Object>> page =
          readOnly.execute(
              s ->
                  jdbc.query(
                      select
                          + " AND (recorded_at, event_id) > (?, ?)"
                          + " ORDER BY recorded_at, event_id LIMIT ?",
                      (rs, i) -> {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("recordedAt", rs.getTimestamp("recorded_at").toInstant());
                        filler.fill(rs, record);
                        record.put("_id", rs.getObject("event_id", UUID.class));
                        return record;
                      },
                      subject.organizationId(),
                      pseudonym,
                      Timestamp.from(cursorTime),
                      cursorId,
                      PAGE_SIZE));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section(section);
      }
      for (Map<String, Object> record : page) {
        afterRecorded = (Instant) record.get("recordedAt");
        afterId = (UUID) record.remove("_id");
        writer.write(record);
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  private static String targetKind(String kind) {
    return switch (kind) {
      case "USER" -> "Rechtekontext einer Person";
      case "PERMISSION_PROFILE" -> "Rechteprofil einer Gruppe";
      default -> kind;
    };
  }

  @FunctionalInterface
  private interface RecordFiller {
    void fill(java.sql.ResultSet rs, Map<String, Object> record) throws java.sql.SQLException;
  }
}
