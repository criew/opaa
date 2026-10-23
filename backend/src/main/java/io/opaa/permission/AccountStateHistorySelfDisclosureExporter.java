package io.opaa.permission;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
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
 * The category {@code account-state-history}: the periods in which the person's account was not
 * usable, with state and cause. Read in pages of {@link #PAGE_SIZE}, each in a short read-only
 * transaction.
 */
@Component
public class AccountStateHistorySelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "account-state-history",
          "Kontozustände",
          List.of(
              new Section(
                  "periods",
                  "Zeiträume, in denen Ihr Konto nicht nutzbar war",
                  List.of(
                      Field.of("state", "Zustand", "account_state_history.state"),
                      Field.of("cause", "Ursache", "account_state_history.cause"),
                      Field.of("validFrom", "Von", "account_state_history.valid_from"),
                      Field.of("validTo", "Bis", "account_state_history.valid_to")))));

  static final int PAGE_SIZE = 500;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public AccountStateHistorySelfDisclosureExporter(
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
    RecordWriter writer = null;
    Instant afterFrom = Instant.EPOCH.minusSeconds(1);
    UUID afterId = new UUID(0, 0);
    while (true) {
      Instant cursorFrom = afterFrom;
      UUID cursorId = afterId;
      List<Map<String, Object>> page =
          readOnly.execute(status -> page(subject, cursorFrom, cursorId));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section("periods");
      }
      for (Map<String, Object> row : page) {
        afterFrom = (Instant) row.get("validFrom");
        afterId = (UUID) row.remove("_id");
        writer.write(row);
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  private List<Map<String, Object>> page(
      SelfDisclosureSubject subject, Instant afterFrom, UUID afterId) {
    return jdbc.query(
        "SELECT id, state, cause, valid_from, valid_to FROM account_state_history"
            + " WHERE user_id = ? AND organization_id = ? AND (valid_from, id) > (?, ?)"
            + " ORDER BY valid_from, id LIMIT ?",
        (rs, i) -> {
          Timestamp validTo = rs.getTimestamp("valid_to");
          Map<String, Object> record = new LinkedHashMap<>();
          record.put("state", rs.getString("state"));
          record.put("cause", rs.getString("cause"));
          record.put("validFrom", rs.getTimestamp("valid_from").toInstant());
          record.put("validTo", validTo == null ? null : validTo.toInstant());
          record.put("_id", rs.getObject("id", UUID.class));
          return record;
        },
        subject.accountId(),
        subject.organizationId(),
        Timestamp.from(afterFrom),
        afterId,
        PAGE_SIZE);
  }
}
