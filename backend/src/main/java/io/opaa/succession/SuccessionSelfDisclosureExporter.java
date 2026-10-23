package io.opaa.succession;

import static io.opaa.common.SelfDisclosureSectionReader.instant;
import static io.opaa.common.SelfDisclosureSectionReader.keyed;
import static io.opaa.common.SelfDisclosureSectionReader.who;

import io.opaa.common.SelfDisclosureSectionReader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code succession} of the self-disclosure: succession cases at assets the person
 * owns ({@code object_type ASSET}) and cases she closed, with the name of their object. Whoever
 * closed a case is "Sie" or "andere Person".
 */
@Component
public class SuccessionSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "succession",
          "Nachfolgefälle",
          List.of(
              new Section(
                  "cases",
                  "Nachfolgefälle an Ihren Assets und von Ihnen abgeschlossene",
                  List.of(
                      Field.of("kind", "Art", "succession_cases.kind"),
                      Field.of("objectType", "Objektart", "succession_cases.object_type"),
                      Field.of("assetType", "Asset-Art", "succession_cases.asset_type"),
                      Field.of(
                          "object",
                          "Objekt",
                          "succession_cases.object_id",
                          "assets.name",
                          "spaces.name",
                          "groups.name"),
                      Field.of("firstSeenAt", "Erstmals erkannt", "succession_cases.first_seen_at"),
                      Field.of("lastSeenAt", "Zuletzt erkannt", "succession_cases.last_seen_at"),
                      Field.of("closedAt", "Abgeschlossen", "succession_cases.closed_at"),
                      Field.derived(
                          "closedBy",
                          "Abgeschlossen von",
                          "succession_cases.closed_by_user_id")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public SuccessionSelfDisclosureExporter(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.reader = new SelfDisclosureSectionReader(transactionManager);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    UUID me = subject.accountId();
    reader.copy(
        sink,
        "cases",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT c.id, c.kind, c.object_type, c.asset_type, c.first_seen_at,"
                    + " c.last_seen_at, c.closed_at, c.closed_by_user_id,"
                    + " CASE c.object_type WHEN 'ASSET' THEN a.name WHEN 'SPACE' THEN s.name"
                    + " WHEN 'GROUP' THEN g.name END AS object_name"
                    + " FROM succession_cases c"
                    + " LEFT JOIN assets a ON c.object_type = 'ASSET' AND a.id = c.object_id"
                    + " LEFT JOIN spaces s ON c.object_type = 'SPACE' AND s.id = c.object_id"
                    + " LEFT JOIN groups g ON c.object_type = 'GROUP' AND g.id = c.object_id"
                    + " WHERE c.organization_id = ?"
                    + " AND (c.closed_by_user_id = ? OR a.owner_user_id = ?)"
                    + " AND (c.created_at, c.id) > (?, ?) ORDER BY c.created_at, c.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("kind", rs.getString("kind"));
                  record.put("objectType", rs.getString("object_type"));
                  record.put("assetType", rs.getString("asset_type"));
                  record.put("object", rs.getString("object_name"));
                  record.put("firstSeenAt", instant(rs.getTimestamp("first_seen_at")));
                  record.put("lastSeenAt", instant(rs.getTimestamp("last_seen_at")));
                  record.put("closedAt", instant(rs.getTimestamp("closed_at")));
                  record.put(
                      "closedBy", who(rs.getObject("closed_by_user_id", UUID.class), me, null));
                  return record;
                },
                subject.organizationId(),
                me,
                me,
                afterTime,
                afterId,
                limit));
  }
}
