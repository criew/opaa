package io.opaa.space;

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
 * The category {@code space-membership-history} of the self-disclosure: the intervals of the
 * person's own space memberships and the membership changes she brought about. Another member is
 * "andere Person", a group member its group, a change without acting person "System".
 */
@Component
public class SpaceMembershipHistorySelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "space-membership-history",
          "Historie der Space-Mitgliedschaften",
          List.of(
              new Section(
                  "intervals",
                  "Mitgliedschaftsintervalle",
                  List.of(
                      Field.of(
                          "space", "Space", "space_membership_history.space_id", "spaces.name"),
                      Field.of(
                          "memberType", "Mitgliedsart", "space_membership_history.subject_type"),
                      Field.derived(
                          "member", "Mitglied", "space_membership_history.subject_user_id"),
                      Field.of(
                          "memberGroup",
                          "Mitgliedsgruppe",
                          "space_membership_history.subject_group_id",
                          "groups.name"),
                      Field.of("role", "Rolle", "space_membership_history.role"),
                      Field.of("cause", "Anlass", "space_membership_history.cause"),
                      Field.derived(
                          "actor", "Bewirkt von", "space_membership_history.actor_user_id"),
                      Field.of("validFrom", "Gültig ab", "space_membership_history.valid_from"),
                      Field.of("validTo", "Gültig bis", "space_membership_history.valid_to")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public SpaceMembershipHistorySelfDisclosureExporter(
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
        "intervals",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT h.id, s.name AS space_name, h.subject_type, h.subject_user_id,"
                    + " g.name AS group_name, h.role, h.cause, h.actor_user_id, h.valid_from,"
                    + " h.valid_to FROM space_membership_history h"
                    + " LEFT JOIN spaces s ON s.id = h.space_id"
                    + " LEFT JOIN groups g ON g.id = h.subject_group_id"
                    + " WHERE h.organization_id = ? AND (h.subject_user_id = ? OR h.actor_user_id = ?)"
                    + " AND (h.valid_from, h.id) > (?, ?) ORDER BY h.valid_from, h.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "valid_from", "id");
                  record.put("space", rs.getString("space_name"));
                  record.put("memberType", rs.getString("subject_type"));
                  record.put("member", who(rs.getObject("subject_user_id", UUID.class), me, null));
                  record.put("memberGroup", rs.getString("group_name"));
                  record.put("role", rs.getString("role"));
                  record.put("cause", rs.getString("cause"));
                  record.put(
                      "actor",
                      who(
                          rs.getObject("actor_user_id", UUID.class),
                          me,
                          SelfDisclosureLabels.SYSTEM));
                  record.put("validFrom", instant(rs.getTimestamp("valid_from")));
                  record.put("validTo", instant(rs.getTimestamp("valid_to")));
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
