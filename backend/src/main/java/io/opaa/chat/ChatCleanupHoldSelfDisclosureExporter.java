package io.opaa.chat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code chat-cleanup-holds} of the self-disclosure: since when the deletion of the
 * person's chats after a lock is held. Only the administration sets a hold, so it is held by {@link
 * SelfDisclosureLabels#OTHER_PERSON}; no reason is stored. A continued deletion removes the row and
 * leaves its trace in the audit log.
 */
@Component
public class ChatCleanupHoldSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "chat-cleanup-holds",
          "Angehaltene Löschung Ihrer Chats",
          List.of(
              new Section(
                  "holds",
                  "Löschung Ihrer Chats nach einer Sperre angehalten",
                  List.of(
                      Field.of("heldSince", "Angehalten seit", "chat_cleanup_holds.held_at"),
                      Field.derived("heldBy", "Angehalten von", "chat_cleanup_holds.held_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public ChatCleanupHoldSelfDisclosureExporter(
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
        "holds",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT user_id, held_at FROM chat_cleanup_holds WHERE user_id = ?"
                    + " AND organization_id = ? AND (held_at, user_id) > (?, ?)"
                    + " ORDER BY held_at, user_id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record =
                      SelfDisclosureSectionReader.keyed(rs, "held_at", "user_id");
                  record.put("heldSince", rs.getTimestamp("held_at").toInstant());
                  record.put("heldBy", SelfDisclosureLabels.OTHER_PERSON);
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
  }
}
