package io.opaa.llm;

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
 * The category {@code transcription-vocabularies} of the self-disclosure: the person's own
 * vocabulary list for speech input and when she last changed it.
 */
@Component
public class TranscriptionVocabularySelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "transcription-vocabularies",
          "Begriffsliste der Spracheingabe",
          List.of(
              new Section(
                  "vocabulary",
                  "Ihre Begriffsliste",
                  List.of(
                      Field.of("vocabulary", "Begriffe", "transcription_vocabularies.vocabulary"),
                      Field.of(
                          "updatedAt", "Geändert", "transcription_vocabularies.updated_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public TranscriptionVocabularySelfDisclosureExporter(
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
        "vocabulary",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT v.user_id, v.vocabulary, v.updated_at FROM transcription_vocabularies v"
                    + " WHERE v.user_id = ? AND v.organization_id = ?"
                    + " AND (v.updated_at, v.user_id) > (?, ?)"
                    + " ORDER BY v.updated_at, v.user_id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "updated_at", "user_id");
                  record.put("vocabulary", rs.getString("vocabulary"));
                  record.put("updatedAt", instant(rs.getTimestamp("updated_at")));
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
  }
}
