package io.opaa.externalaccess;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code settings-editors}: which managed settings the person changed last, with the
 * area and the time, never their values - sender and login name of the mail settings belong to the
 * mail service account, the server password is a secret. Lives in the highest module whose table it
 * reads ({@code external_access_settings}); the other tables are identity's. One read: a setting
 * has one row, a mail template one per key and language.
 */
@Component
public class SettingsEditorsSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "settings-editors",
          "Von Ihnen geänderte Einstellungen",
          List.of(
              new Section(
                  "settings",
                  "Einstellungen, die Sie zuletzt geändert haben",
                  List.of(
                      Field.of(
                          "area",
                          "Bereich",
                          "local_auth_settings.updated_by",
                          "mail_settings.updated_by",
                          "mail_templates.updated_by",
                          "external_access_settings.updated_by",
                          "self_disclosure_settings.updated_by",
                          "link_target_settings.updated_by"),
                      Field.of("template", "Vorlage", "mail_templates.template_key"),
                      Field.of("locale", "Sprache der Vorlage", "mail_templates.locale"),
                      Field.of(
                          "updatedAt",
                          "Geändert",
                          "local_auth_settings.updated_at",
                          "mail_settings.updated_at",
                          "mail_templates.updated_at",
                          "external_access_settings.updated_at",
                          "self_disclosure_settings.updated_at",
                          "link_target_settings.updated_at")))));

  private static final String SQL =
      "SELECT 'Lokale Konten' AS area, NULL AS template, NULL AS locale, updated_at"
          + " FROM local_auth_settings WHERE updated_by = ?"
          + " UNION ALL SELECT 'E-Mail-Versand', NULL, NULL, updated_at"
          + " FROM mail_settings WHERE updated_by = ?"
          + " UNION ALL SELECT 'E-Mail-Vorlage', template_key, locale, updated_at"
          + " FROM mail_templates WHERE updated_by = ?"
          + " UNION ALL SELECT 'Zugang externer KI-Werkzeuge', NULL, NULL, updated_at"
          + " FROM external_access_settings WHERE updated_by = ?"
          + " UNION ALL SELECT 'Selbstauskunft', NULL, NULL, updated_at"
          + " FROM self_disclosure_settings WHERE updated_by = ?"
          + " UNION ALL SELECT 'Erlaubte Linkziele', NULL, NULL, updated_at"
          + " FROM link_target_settings WHERE updated_by = ?"
          + " ORDER BY updated_at, area, template, locale";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public SettingsEditorsSelfDisclosureExporter(
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
    Object person = subject.accountId();
    List<Map<String, Object>> rows =
        readOnly.execute(
            status ->
                jdbc.query(
                    SQL,
                    (rs, i) -> {
                      Timestamp updatedAt = rs.getTimestamp("updated_at");
                      Map<String, Object> record = new LinkedHashMap<>();
                      record.put("area", rs.getString("area"));
                      record.put("template", rs.getString("template"));
                      record.put("locale", rs.getString("locale"));
                      record.put("updatedAt", updatedAt == null ? null : updatedAt.toInstant());
                      return record;
                    },
                    person,
                    person,
                    person,
                    person,
                    person,
                    person));
    if (rows == null || rows.isEmpty()) {
      return;
    }
    RecordWriter writer = sink.section("settings");
    for (Map<String, Object> row : rows) {
      writer.write(row);
    }
  }
}
