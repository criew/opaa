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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code permission-transfers} of the self-disclosure: transfers in which the person
 * was source, target or acting. A side is "Sie" or "andere Person"; a group side only shows its
 * kind, its label never goes out (the label of the other side).
 */
@Component
public class PermissionTransferSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "permission-transfers",
          "Rechteübertragungen",
          List.of(
              new Section(
                  "transfers",
                  "Übertragungen, an denen Sie beteiligt waren",
                  List.of(
                      Field.of("sourceType", "Art der Quelle", "permission_transfers.source_type"),
                      Field.derived(
                          "source",
                          "Quelle",
                          "permission_transfers.source_user_id",
                          "permission_transfers.source_label"),
                      Field.of("targetType", "Art des Ziels", "permission_transfers.target_type"),
                      Field.derived(
                          "target",
                          "Ziel",
                          "permission_transfers.target_user_id",
                          "permission_transfers.target_label"),
                      Field.of("scope", "Umfang", "permission_transfers.scope"),
                      Field.of(
                          "grantCount", "Rechte an Assets", "permission_transfers.grant_count"),
                      Field.of(
                          "spaceMembershipCount",
                          "Space-Mitgliedschaften",
                          "permission_transfers.space_membership_count"),
                      Field.of(
                          "capabilityCount",
                          "Anlegerechte",
                          "permission_transfers.capability_count"),
                      Field.of(
                          "ownershipCount", "Eigentum", "permission_transfers.ownership_count"),
                      Field.of(
                          "stewardshipCount",
                          "Gruppenverantwortungen",
                          "permission_transfers.stewardship_count"),
                      Field.derived(
                          "actor", "Ausgeführt von", "permission_transfers.actor_user_id"),
                      Field.of("performedAt", "Zeitpunkt", "permission_transfers.performed_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public PermissionTransferSelfDisclosureExporter(
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
        "transfers",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT id, source_type, source_user_id, target_type, target_user_id, scope,"
                    + " grant_count, space_membership_count, capability_count, ownership_count,"
                    + " stewardship_count, actor_user_id, performed_at FROM permission_transfers"
                    + " WHERE organization_id = ?"
                    + " AND (source_user_id = ? OR target_user_id = ? OR actor_user_id = ?)"
                    + " AND (performed_at, id) > (?, ?) ORDER BY performed_at, id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "performed_at", "id");
                  record.put("sourceType", rs.getString("source_type"));
                  record.put("source", who(rs.getObject("source_user_id", UUID.class), me, null));
                  record.put("targetType", rs.getString("target_type"));
                  record.put("target", who(rs.getObject("target_user_id", UUID.class), me, null));
                  record.put("scope", rs.getString("scope"));
                  record.put("grantCount", rs.getInt("grant_count"));
                  record.put("spaceMembershipCount", rs.getInt("space_membership_count"));
                  record.put("capabilityCount", rs.getInt("capability_count"));
                  record.put("ownershipCount", rs.getInt("ownership_count"));
                  record.put("stewardshipCount", rs.getInt("stewardship_count"));
                  record.put(
                      "actor",
                      who(
                          rs.getObject("actor_user_id", UUID.class),
                          me,
                          SelfDisclosureLabels.SYSTEM));
                  record.put("performedAt", instant(rs.getTimestamp("performed_at")));
                  return record;
                },
                subject.organizationId(),
                me,
                me,
                me,
                afterTime,
                afterId,
                limit));
  }
}
