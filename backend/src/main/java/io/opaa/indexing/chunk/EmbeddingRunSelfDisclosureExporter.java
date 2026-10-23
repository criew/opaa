package io.opaa.indexing.chunk;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code indexing-runs} of the self-disclosure: the re-embedding run the person
 * started, with start, end and result. Only {@code embedding_index} names a person; it keeps the
 * latest run alone, so there is at most one record. An indexing job records only how it was
 * triggered (manual, schedule, webhook), never by whom.
 */
@Component
public class EmbeddingRunSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "indexing-runs",
          "Indexierungsläufe",
          List.of(
              new Section(
                  "reembeddings",
                  "Von Ihnen gestartete Neueinbettung",
                  List.of(
                      Field.of("startedAt", "Beginn", "embedding_index.run_started_at"),
                      Field.of("finishedAt", "Ende", "embedding_index.run_finished_at"),
                      Field.of("result", "Ergebnis", "embedding_index.run_status")))));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public EmbeddingRunSelfDisclosureExporter(
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
    List<Map<String, Object>> runs =
        readOnly.execute(
            s ->
                jdbc.query(
                    "SELECT run_started_at, run_finished_at, run_status FROM embedding_index"
                        + " WHERE run_started_by = ? AND run_organization_id = ?",
                    (rs, i) -> {
                      Map<String, Object> record = new LinkedHashMap<>();
                      record.put("startedAt", instant(rs.getTimestamp("run_started_at")));
                      record.put("finishedAt", instant(rs.getTimestamp("run_finished_at")));
                      record.put("result", result(rs.getString("run_status")));
                      return record;
                    },
                    subject.accountId(),
                    subject.organizationId()));
    if (runs == null || runs.isEmpty()) {
      return;
    }
    RecordWriter writer = sink.section("reembeddings");
    for (Map<String, Object> run : runs) {
      writer.write(run);
    }
  }

  /** The German word for a run status. */
  static String result(String status) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case "RUNNING" -> "läuft";
      case "COMPLETED" -> "abgeschlossen";
      case "FAILED" -> "fehlgeschlagen";
      case "CANCELLED" -> "abgebrochen";
      default -> status;
    };
  }

  private static java.time.Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
