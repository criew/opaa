package io.opaa.chat;

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
 * The category {@code chat-attachments} of the self-disclosure: the person's chat attachments as a
 * directory - name and when it was attached. The text stays out like the contents of a private
 * library; the person downloads the original from the chat or from "Meine Uploads" herself.
 */
@Component
public class ChatAttachmentSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "chat-attachments",
          "Anhänge an Ihren Chatfragen",
          List.of(
              new Section(
                  "attachments",
                  "Angehängte Dokumente",
                  List.of(
                      Field.of("fileName", "Dateiname", "chat_attachments.file_name"),
                      Field.of("attachedAt", "Angehängt am", "chat_attachments.created_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public ChatAttachmentSelfDisclosureExporter(
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
        "attachments",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT id, file_name, created_at FROM chat_attachments"
                    + " WHERE author_id = ? AND organization_id = ? AND (created_at, id) > (?, ?)"
                    + " ORDER BY created_at, id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record =
                      SelfDisclosureSectionReader.keyed(rs, "created_at", "id");
                  record.put("fileName", rs.getString("file_name"));
                  record.put("attachedAt", rs.getTimestamp("created_at").toInstant());
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
  }
}
