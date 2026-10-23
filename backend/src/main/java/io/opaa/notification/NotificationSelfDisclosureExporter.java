package io.opaa.notification;

import io.opaa.api.types.NotificationType;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code notifications} of the self-disclosure: the person's own notifications with
 * type, object type, title, text, arrival and when she read them. Read in pages of {@link
 * #PAGE_SIZE}, each in a short read-only transaction, written after it ended. A type whose text
 * names another person goes out without title and text ({@link #TYPES_NAMING_PERSONS}).
 */
@Component
public class NotificationSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "notifications",
          "Benachrichtigungen",
          List.of(
              new Section(
                  "notifications",
                  "Ihre Benachrichtigungen",
                  List.of(
                      Field.of("type", "Art", "notifications.type"),
                      Field.of("objectType", "Objektart", "notifications.object_type"),
                      Field.of("title", "Titel", "notifications.title"),
                      Field.of("body", "Text", "notifications.body"),
                      Field.of("receivedAt", "Eingang", "notifications.created_at"),
                      Field.of("readAt", "Gelesen", "notifications.read_at")))));

  /** Types whose title or text names another person; none today. */
  static final Set<NotificationType> TYPES_NAMING_PERSONS = Set.of();

  static final int PAGE_SIZE = 500;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public NotificationSelfDisclosureExporter(
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
    Instant afterCreatedAt = Instant.EPOCH.minusSeconds(1);
    UUID afterId = new UUID(0, 0);
    while (true) {
      Instant cursorTime = afterCreatedAt;
      UUID cursorId = afterId;
      List<Map<String, Object>> page = readOnly.execute(s -> page(subject, cursorTime, cursorId));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section("notifications");
      }
      for (Map<String, Object> row : page) {
        afterCreatedAt = (Instant) row.remove("_createdAt");
        afterId = (UUID) row.remove("_id");
        writer.write(row);
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  private List<Map<String, Object>> page(
      SelfDisclosureSubject subject, Instant afterCreatedAt, UUID afterId) {
    return jdbc.query(
        "SELECT id, type, object_type, title, body, created_at, read_at FROM notifications"
            + " WHERE recipient_user_id = ? AND organization_id = ?"
            + " AND (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT ?",
        (rs, i) -> {
          String type = rs.getString("type");
          boolean withoutText = namesPersons(type);
          Instant createdAt = rs.getTimestamp("created_at").toInstant();
          Timestamp readAt = rs.getTimestamp("read_at");
          Map<String, Object> record = new LinkedHashMap<>();
          record.put("type", type);
          record.put("objectType", rs.getString("object_type"));
          record.put("title", withoutText ? null : rs.getString("title"));
          record.put("body", withoutText ? null : rs.getString("body"));
          record.put("receivedAt", createdAt);
          record.put("readAt", readAt == null ? null : readAt.toInstant());
          record.put("_createdAt", createdAt);
          record.put("_id", rs.getObject("id", UUID.class));
          return record;
        },
        subject.accountId(),
        subject.organizationId(),
        Timestamp.from(afterCreatedAt),
        afterId,
        PAGE_SIZE);
  }

  private static boolean namesPersons(String type) {
    return TYPES_NAMING_PERSONS.stream().anyMatch(t -> t.name().equals(type));
  }
}
