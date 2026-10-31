package io.opaa.chat;

import io.opaa.api.types.ChatCleanupHoldPurpose;
import io.opaa.audit.ChatCleanupHoldPurposeLabels;
import io.opaa.common.SelfDisclosureSectionReader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code chat-cleanup-holds} of the self-disclosure: since when, until when and for
 * which purpose of the closed list the deletion of the person's chats after a lock is held. Only
 * the administration sets a hold, so it is held by {@link SelfDisclosureLabels#OTHER_PERSON}. The
 * free-text reason stays out, as it may name an order the person is not yet to learn of. Request,
 * approval, rejection and end show with their purpose in the protocol part of the self-disclosure;
 * an ended hold removes the row.
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
                      Field.of("heldUntil", "Angehalten bis", "chat_cleanup_holds.held_until"),
                      Field.of("purpose", "Zweck", "chat_cleanup_holds.purpose"),
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
                "SELECT user_id, held_at, held_until, purpose FROM chat_cleanup_holds"
                    + " WHERE user_id = ?"
                    + " AND organization_id = ? AND (held_at, user_id) > (?, ?)"
                    + " ORDER BY held_at, user_id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record =
                      SelfDisclosureSectionReader.keyed(rs, "held_at", "user_id");
                  record.put("heldSince", rs.getTimestamp("held_at").toInstant());
                  Timestamp heldUntil = rs.getTimestamp("held_until");
                  record.put("heldUntil", heldUntil == null ? null : heldUntil.toInstant());
                  String purpose = rs.getString("purpose");
                  record.put(
                      "purpose",
                      purpose == null
                          ? null
                          : ChatCleanupHoldPurposeLabels.label(
                              ChatCleanupHoldPurpose.valueOf(purpose)));
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
