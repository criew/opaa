package io.opaa.asset;

import static io.opaa.common.SelfDisclosureSectionReader.instant;
import static io.opaa.common.SelfDisclosureSectionReader.keyed;
import static io.opaa.common.SelfDisclosureSectionReader.who;

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
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code rights} of the self-disclosure: the assets the person owns or created, with
 * type and name only (prompt libraries included, their texts never), the grants she holds or gave
 * and the capabilities she holds or gave. A grant to a group or to "Alle Konten" appears only when
 * she gave it; another person is "andere Person", a grant without granting person "System".
 */
@Component
public class RightsSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "rights",
          "Rechte und eigene Assets",
          List.of(
              new Section(
                  "assets",
                  "Ihre Assets",
                  List.of(
                      Field.of("assetType", "Art", "assets.asset_type"),
                      Field.of("name", "Name", "assets.name"),
                      Field.derived("owner", "Eigentum", "assets.owner_user_id"),
                      Field.of(
                          "ownerGroup", "Eigentümergruppe", "assets.owner_group_id", "groups.name"),
                      Field.derived("createdBy", "Angelegt von", "assets.created_by_user_id"),
                      Field.of("ownerOnly", "Nur für die Eigentümerin", "assets.owner_only"),
                      Field.of("createdAt", "Angelegt", "assets.created_at"))),
              new Section(
                  "grants",
                  "Rechte an Assets, die Sie halten oder vergeben haben",
                  List.of(
                      Field.of("assetType", "Art", "asset_grants.asset_type"),
                      Field.of("asset", "Asset", "asset_grants.asset_id", "assets.name"),
                      Field.of("recipientType", "Empfängerart", "asset_grants.subject_type"),
                      Field.derived("recipient", "Empfänger", "asset_grants.subject_user_id"),
                      Field.of(
                          "recipientGroup",
                          "Empfängergruppe",
                          "asset_grants.subject_group_id",
                          "groups.name"),
                      Field.of("role", "Rolle", "asset_grants.role"),
                      Field.of("expiresAt", "Befristet bis", "asset_grants.expires_at"),
                      Field.derived("grantedBy", "Vergeben von", "asset_grants.granted_by_user_id"),
                      Field.of("createdAt", "Vergeben", "asset_grants.created_at"),
                      Field.of("updatedAt", "Zuletzt geändert", "asset_grants.updated_at"))),
              new Section(
                  "capabilities",
                  "Anlegerechte, die Sie halten oder vergeben haben",
                  List.of(
                      Field.of("capability", "Recht", "capability_grants.capability"),
                      Field.of("scope", "Geltungsbereich", "capability_grants.scope"),
                      Field.of("recipientType", "Empfängerart", "capability_grants.subject_type"),
                      Field.derived("recipient", "Empfänger", "capability_grants.subject_user_id"),
                      Field.of(
                          "recipientGroup",
                          "Empfängergruppe",
                          "capability_grants.subject_group_id",
                          "groups.name"),
                      Field.derived(
                          "grantedBy", "Vergeben von", "capability_grants.granted_by_user_id"),
                      Field.of("createdAt", "Vergeben", "capability_grants.created_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public RightsSelfDisclosureExporter(
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
        "assets",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT a.id, a.asset_type, a.name, a.owner_user_id, g.name AS owner_group,"
                    + " a.created_by_user_id, a.owner_only, a.created_at FROM assets a"
                    + " LEFT JOIN groups g ON g.id = a.owner_group_id"
                    + " WHERE a.organization_id = ?"
                    + " AND (a.owner_user_id = ? OR a.created_by_user_id = ?)"
                    + " AND (a.created_at, a.id) > (?, ?) ORDER BY a.created_at, a.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("assetType", rs.getString("asset_type"));
                  record.put("name", rs.getString("name"));
                  record.put("owner", who(rs.getObject("owner_user_id", UUID.class), me, null));
                  record.put("ownerGroup", rs.getString("owner_group"));
                  record.put(
                      "createdBy", who(rs.getObject("created_by_user_id", UUID.class), me, null));
                  record.put("ownerOnly", rs.getBoolean("owner_only"));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
                  return record;
                },
                subject.organizationId(),
                me,
                me,
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "grants",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "WITH ag AS (SELECT * FROM asset_grants WHERE organization_id = ?"
                    + " AND (subject_user_id = ? OR granted_by_user_id = ?)"
                    + " AND (created_at, id) > (?, ?))"
                    + " SELECT ag.id, ag.asset_type, a.name AS asset_name, ag.subject_type,"
                    + " ag.subject_user_id, g.name AS group_name, ag.role, ag.expires_at,"
                    + " ag.granted_by_user_id, ag.created_at, ag.updated_at FROM ag"
                    + " LEFT JOIN assets a ON a.id = ag.asset_id"
                    + " LEFT JOIN groups g ON g.id = ag.subject_group_id"
                    + " ORDER BY ag.created_at, ag.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("assetType", rs.getString("asset_type"));
                  record.put("asset", rs.getString("asset_name"));
                  record.put("recipientType", rs.getString("subject_type"));
                  record.put(
                      "recipient", who(rs.getObject("subject_user_id", UUID.class), me, null));
                  record.put("recipientGroup", rs.getString("group_name"));
                  record.put("role", rs.getString("role"));
                  record.put("expiresAt", instant(rs.getTimestamp("expires_at")));
                  record.put(
                      "grantedBy",
                      who(
                          rs.getObject("granted_by_user_id", UUID.class),
                          me,
                          SelfDisclosureLabels.SYSTEM));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
                  record.put("updatedAt", instant(rs.getTimestamp("updated_at")));
                  return record;
                },
                subject.organizationId(),
                me,
                me,
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "capabilities",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT c.id, c.capability, c.scope, c.subject_type, c.subject_user_id,"
                    + " g.name AS group_name, c.granted_by_user_id, c.created_at"
                    + " FROM capability_grants c LEFT JOIN groups g ON g.id = c.subject_group_id"
                    + " WHERE c.organization_id = ?"
                    + " AND (c.subject_user_id = ? OR c.granted_by_user_id = ?)"
                    + " AND (c.created_at, c.id) > (?, ?) ORDER BY c.created_at, c.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("capability", rs.getString("capability"));
                  record.put("scope", rs.getString("scope"));
                  record.put("recipientType", rs.getString("subject_type"));
                  record.put(
                      "recipient", who(rs.getObject("subject_user_id", UUID.class), me, null));
                  record.put("recipientGroup", rs.getString("group_name"));
                  record.put(
                      "grantedBy",
                      who(
                          rs.getObject("granted_by_user_id", UUID.class),
                          me,
                          SelfDisclosureLabels.SYSTEM));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
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
