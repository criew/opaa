package io.opaa.group;

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
 * The category {@code groups} of the self-disclosure: the person's group memberships, the groups
 * she is responsible for and the responsibilities she appointed. Another person is "andere Person",
 * an appointment without appointing person "System".
 */
@Component
public class GroupSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "groups",
          "Gruppen",
          List.of(
              new Section(
                  "memberships",
                  "Ihre Gruppenmitgliedschaften",
                  List.of(
                      Field.of("group", "Gruppe", "group_memberships.group_id", "groups.name"),
                      Field.of("groupKind", "Gruppenart", "groups.kind"),
                      Field.of("createdAt", "Seit", "group_memberships.created_at"))),
              new Section(
                  "stewardships",
                  "Verantwortung für Gruppen",
                  List.of(
                      Field.of("group", "Gruppe", "group_stewards.group_id", "groups.name"),
                      Field.derived("steward", "Verantwortlich", "group_stewards.user_id"),
                      Field.derived(
                          "appointedBy", "Benannt von", "group_stewards.appointed_by_user_id"),
                      Field.of("createdAt", "Seit", "group_stewards.created_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public GroupSelfDisclosureExporter(
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
        "memberships",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT m.id, g.name, g.kind, m.created_at FROM group_memberships m"
                    + " JOIN groups g ON g.id = m.group_id"
                    + " WHERE m.user_id = ? AND m.organization_id = ?"
                    + " AND (m.created_at, m.id) > (?, ?) ORDER BY m.created_at, m.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("group", rs.getString("name"));
                  record.put("groupKind", rs.getString("kind"));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
                  return record;
                },
                me,
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "stewardships",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT s.id, g.name, s.user_id, s.appointed_by_user_id, s.created_at"
                    + " FROM group_stewards s JOIN groups g ON g.id = s.group_id"
                    + " WHERE s.organization_id = ? AND (s.user_id = ? OR s.appointed_by_user_id = ?)"
                    + " AND (s.created_at, s.id) > (?, ?) ORDER BY s.created_at, s.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("group", rs.getString("name"));
                  record.put("steward", who(rs.getObject("user_id", UUID.class), me, null));
                  record.put(
                      "appointedBy",
                      who(
                          rs.getObject("appointed_by_user_id", UUID.class),
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
