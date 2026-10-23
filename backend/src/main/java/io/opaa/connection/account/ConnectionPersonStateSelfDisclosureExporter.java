package io.opaa.connection.account;

import static io.opaa.common.SelfDisclosureSectionReader.instant;
import static io.opaa.common.SelfDisclosureSectionReader.keyed;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code connection-person-states} of the self-disclosure: whether and since when the
 * person's connections rest or are deactivated, and when that was last checked; at most one row.
 */
@Component
public class ConnectionPersonStateSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "connection-person-states",
          "Ruhezustand Ihrer Verbindungen",
          List.of(
              new Section(
                  "state",
                  "Zustand Ihrer Verbindungen",
                  List.of(
                      Field.of(
                          "deactivatedSince",
                          "Deaktiviert seit",
                          "connection_person_states.deactivated_since"),
                      Field.of(
                          "dormantSince", "Ruhend seit", "connection_person_states.dormant_since"),
                      Field.of(
                          "checkedAt",
                          "Zuletzt geprüft",
                          "connection_person_states.checked_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public ConnectionPersonStateSelfDisclosureExporter(
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
    reader.copy(
        sink,
        "state",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT user_id, deactivated_since, dormant_since, checked_at"
                    + " FROM connection_person_states WHERE user_id = ?"
                    + " AND (checked_at, user_id) > (?, ?) ORDER BY checked_at, user_id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "checked_at", "user_id");
                  record.put("deactivatedSince", instant(rs.getTimestamp("deactivated_since")));
                  record.put("dormantSince", instant(rs.getTimestamp("dormant_since")));
                  record.put("checkedAt", instant(rs.getTimestamp("checked_at")));
                  return record;
                },
                subject.accountId(),
                afterTime,
                afterId,
                limit));
  }
}
