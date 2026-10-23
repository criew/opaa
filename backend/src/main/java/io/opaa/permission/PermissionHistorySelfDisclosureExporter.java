package io.opaa.permission;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code permission-history} of the self-disclosure: the intervals of the rights
 * history in which the person was subject, owner or group member, and those she brought about. It
 * reads her own history rows directly, page by page, never through the point-in-time query. Another
 * person is "andere Person", a change without acting person "System".
 */
@Component
public class PermissionHistorySelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "permission-history",
          "Rechtehistorie",
          List.of(
              new Section(
                  "assetGrants",
                  "Rechte an Assets",
                  List.of(
                      Field.of("assetType", "Art", "asset_grant_history.asset_type"),
                      Field.of("asset", "Asset", "asset_grant_history.asset_id", "assets.name"),
                      Field.of("recipientType", "Empfängerart", "asset_grant_history.subject_type"),
                      Field.derived(
                          "recipient", "Empfänger", "asset_grant_history.subject_user_id"),
                      Field.of(
                          "recipientGroup",
                          "Empfängergruppe",
                          "asset_grant_history.subject_group_id",
                          "groups.name"),
                      Field.of("role", "Rolle", "asset_grant_history.role"),
                      Field.of("expiresAt", "Befristet bis", "asset_grant_history.expires_at"),
                      Field.of("cause", "Anlass", "asset_grant_history.cause"),
                      Field.derived("actor", "Bewirkt von", "asset_grant_history.actor_user_id"),
                      Field.of("validFrom", "Gültig ab", "asset_grant_history.valid_from"),
                      Field.of("validTo", "Gültig bis", "asset_grant_history.valid_to"))),
              new Section(
                  "ownership",
                  "Eigentum an Assets",
                  List.of(
                      Field.of("assetType", "Art", "asset_ownership_history.asset_type"),
                      Field.of("asset", "Asset", "asset_ownership_history.asset_id", "assets.name"),
                      Field.of("ownerType", "Eigentümerart", "asset_ownership_history.owner_type"),
                      Field.derived("owner", "Eigentum", "asset_ownership_history.owner_user_id"),
                      Field.of(
                          "ownerGroup",
                          "Eigentümergruppe",
                          "asset_ownership_history.owner_group_id",
                          "groups.name"),
                      Field.of("cause", "Anlass", "asset_ownership_history.cause"),
                      Field.derived(
                          "actor", "Bewirkt von", "asset_ownership_history.actor_user_id"),
                      Field.of("validFrom", "Gültig ab", "asset_ownership_history.valid_from"),
                      Field.of("validTo", "Gültig bis", "asset_ownership_history.valid_to"))),
              new Section(
                  "visibility",
                  "Von Ihnen geänderte Sichtbarkeit",
                  List.of(
                      Field.of("assetType", "Art", "asset_visibility_history.asset_type"),
                      Field.of(
                          "asset", "Asset", "asset_visibility_history.asset_id", "assets.name"),
                      Field.of("cause", "Anlass", "asset_visibility_history.cause"),
                      Field.of(
                          "externalAccessState",
                          "Freigabe für externe KI-Werkzeuge",
                          "asset_visibility_history.external_access_state"),
                      Field.of(
                          "externalAccessExpiresAt",
                          "Freigabe bis",
                          "asset_visibility_history.external_access_expires_at"),
                      Field.derived(
                          "actor", "Bewirkt von", "asset_visibility_history.actor_user_id"),
                      Field.of("validFrom", "Gültig ab", "asset_visibility_history.valid_from"),
                      Field.of("validTo", "Gültig bis", "asset_visibility_history.valid_to"))),
              new Section(
                  "capabilities",
                  "Anlegerechte",
                  List.of(
                      Field.of("capability", "Recht", "capability_grant_history.capability"),
                      Field.of("scope", "Geltungsbereich", "capability_grant_history.scope"),
                      Field.of(
                          "recipientType", "Empfängerart", "capability_grant_history.subject_type"),
                      Field.derived(
                          "recipient", "Empfänger", "capability_grant_history.subject_user_id"),
                      Field.of(
                          "recipientGroup",
                          "Empfängergruppe",
                          "capability_grant_history.subject_group_id",
                          "groups.name"),
                      Field.of("cause", "Anlass", "capability_grant_history.cause"),
                      Field.derived(
                          "actor", "Bewirkt von", "capability_grant_history.actor_user_id"),
                      Field.of("validFrom", "Gültig ab", "capability_grant_history.valid_from"),
                      Field.of("validTo", "Gültig bis", "capability_grant_history.valid_to"))),
              new Section(
                  "groupMemberships",
                  "Gruppenmitgliedschaften",
                  List.of(
                      Field.of(
                          "group", "Gruppe", "group_membership_history.group_id", "groups.name"),
                      Field.derived("member", "Mitglied", "group_membership_history.user_id"),
                      Field.of("cause", "Anlass", "group_membership_history.cause"),
                      Field.derived(
                          "actor", "Bewirkt von", "group_membership_history.actor_user_id"),
                      Field.of("validFrom", "Gültig ab", "group_membership_history.valid_from"),
                      Field.of("validTo", "Gültig bis", "group_membership_history.valid_to")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public PermissionHistorySelfDisclosureExporter(
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
    UUID organization = subject.organizationId();
    reader.copy(
        sink,
        "assetGrants",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "WITH h AS (SELECT * FROM asset_grant_history WHERE organization_id = ?"
                    + " AND (subject_user_id = ? OR actor_user_id = ?)"
                    + " AND (valid_from, id) > (?, ?))"
                    + " SELECT h.id, h.asset_type, a.name AS asset_name, h.subject_type,"
                    + " h.subject_user_id, g.name AS group_name, h.role, h.expires_at, h.cause,"
                    + " h.actor_user_id, h.valid_from, h.valid_to FROM h"
                    + " LEFT JOIN assets a ON a.id = h.asset_id"
                    + " LEFT JOIN groups g ON g.id = h.subject_group_id"
                    + " ORDER BY h.valid_from, h.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "valid_from", "id");
                  record.put("assetType", rs.getString("asset_type"));
                  record.put("asset", rs.getString("asset_name"));
                  record.put("recipientType", rs.getString("subject_type"));
                  record.put(
                      "recipient", who(rs.getObject("subject_user_id", UUID.class), me, null));
                  record.put("recipientGroup", rs.getString("group_name"));
                  record.put("role", rs.getString("role"));
                  record.put("expiresAt", instant(rs.getTimestamp("expires_at")));
                  putChange(record, rs, me);
                  return record;
                },
                organization,
                me,
                me,
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "ownership",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT h.id, h.asset_type, a.name AS asset_name, h.owner_type, h.owner_user_id,"
                    + " g.name AS group_name, h.cause, h.actor_user_id, h.valid_from, h.valid_to"
                    + " FROM asset_ownership_history h LEFT JOIN assets a ON a.id = h.asset_id"
                    + " LEFT JOIN groups g ON g.id = h.owner_group_id"
                    + " WHERE h.organization_id = ? AND (h.owner_user_id = ? OR h.actor_user_id = ?)"
                    + " AND (h.valid_from, h.id) > (?, ?) ORDER BY h.valid_from, h.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "valid_from", "id");
                  record.put("assetType", rs.getString("asset_type"));
                  record.put("asset", rs.getString("asset_name"));
                  record.put("ownerType", rs.getString("owner_type"));
                  record.put("owner", who(rs.getObject("owner_user_id", UUID.class), me, null));
                  record.put("ownerGroup", rs.getString("group_name"));
                  putChange(record, rs, me);
                  return record;
                },
                organization,
                me,
                me,
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "visibility",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT h.id, h.asset_type, a.name AS asset_name, h.cause,"
                    + " h.external_access_state, h.external_access_expires_at, h.actor_user_id,"
                    + " h.valid_from, h.valid_to FROM asset_visibility_history h"
                    + " LEFT JOIN assets a ON a.id = h.asset_id"
                    + " WHERE h.organization_id = ? AND h.actor_user_id = ?"
                    + " AND (h.valid_from, h.id) > (?, ?) ORDER BY h.valid_from, h.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "valid_from", "id");
                  record.put("assetType", rs.getString("asset_type"));
                  record.put("asset", rs.getString("asset_name"));
                  record.put("externalAccessState", rs.getString("external_access_state"));
                  record.put(
                      "externalAccessExpiresAt",
                      instant(rs.getTimestamp("external_access_expires_at")));
                  putChange(record, rs, me);
                  return record;
                },
                organization,
                me,
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "capabilities",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT h.id, h.capability, h.scope, h.subject_type, h.subject_user_id,"
                    + " g.name AS group_name, h.cause, h.actor_user_id, h.valid_from, h.valid_to"
                    + " FROM capability_grant_history h"
                    + " LEFT JOIN groups g ON g.id = h.subject_group_id"
                    + " WHERE h.organization_id = ? AND (h.subject_user_id = ? OR h.actor_user_id = ?)"
                    + " AND (h.valid_from, h.id) > (?, ?) ORDER BY h.valid_from, h.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "valid_from", "id");
                  record.put("capability", rs.getString("capability"));
                  record.put("scope", rs.getString("scope"));
                  record.put("recipientType", rs.getString("subject_type"));
                  record.put(
                      "recipient", who(rs.getObject("subject_user_id", UUID.class), me, null));
                  record.put("recipientGroup", rs.getString("group_name"));
                  putChange(record, rs, me);
                  return record;
                },
                organization,
                me,
                me,
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "groupMemberships",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT h.id, g.name AS group_name, h.user_id, h.cause, h.actor_user_id,"
                    + " h.valid_from, h.valid_to FROM group_membership_history h"
                    + " LEFT JOIN groups g ON g.id = h.group_id"
                    + " WHERE h.organization_id = ? AND (h.user_id = ? OR h.actor_user_id = ?)"
                    + " AND (h.valid_from, h.id) > (?, ?) ORDER BY h.valid_from, h.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "valid_from", "id");
                  record.put("group", rs.getString("group_name"));
                  record.put("member", who(rs.getObject("user_id", UUID.class), me, null));
                  putChange(record, rs, me);
                  return record;
                },
                organization,
                me,
                me,
                afterTime,
                afterId,
                limit));
  }

  /** Cause, acting person and interval, the columns every history table shares. */
  private static void putChange(Map<String, Object> record, ResultSet rs, UUID me)
      throws SQLException {
    record.put("cause", rs.getString("cause"));
    record.put(
        "actor", who(rs.getObject("actor_user_id", UUID.class), me, SelfDisclosureLabels.SYSTEM));
    record.put("validFrom", instant(rs.getTimestamp("valid_from")));
    record.put("validTo", instant(rs.getTimestamp("valid_to")));
  }
}
