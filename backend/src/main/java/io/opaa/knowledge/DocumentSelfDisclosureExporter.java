package io.opaa.knowledge;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code documents} of the self-disclosure, a directory only: the person's uploads
 * into libraries other than her private ones (name, date, library), then the documents of her
 * private libraries, connector-fed ones included, with path and folder in the source. The path of
 * an uploaded document is the locator of its stored original and never goes out; contents,
 * originals and technical source keys never either. Read in pages, each in a short read-only
 * transaction.
 */
@Component
public class DocumentSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "documents",
          "Dokumentbestand (nur Verzeichnis)",
          List.of(
              new Section(
                  "uploads",
                  "Von Ihnen hochgeladene Dateien in anderen Bibliotheken",
                  List.of(
                      Field.of("fileName", "Dateiname", "documents.file_name"),
                      Field.of("createdAt", "Datum", "documents.created_at"),
                      Field.of("library", "Bibliothek", "assets.name"))),
              new Section(
                  "privateLibraries",
                  "Dokumente Ihrer privaten Bibliotheken",
                  List.of(
                      Field.of("fileName", "Dateiname", "documents.file_name"),
                      Field.of("createdAt", "Datum", "documents.created_at"),
                      Field.of("library", "Bibliothek", "assets.name"),
                      Field.of("path", "Pfad in der Quelle", "documents.file_path"),
                      Field.of("folder", "Ordner in der Quelle", "documents.source_hierarchy_path"),
                      Field.derived(
                          "uploadedByYou",
                          "Von Ihnen hochgeladen",
                          "documents.uploaded_by_user_id")))));

  static final int PAGE_SIZE = 500;

  /** The documents of the person's private libraries, the anchor of the second section. */
  private static final String PRIVATE_LIBRARY =
      "SELECT 1 FROM assets a WHERE a.id = d.library_id AND a.owner_only AND a.owner_user_id = ?";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public DocumentSelfDisclosureExporter(
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
    writeUploads(subject, sink);
    writePrivateLibraries(subject, sink);
  }

  private void writeUploads(SelfDisclosureSubject subject, SelfDisclosureSink sink)
      throws IOException {
    RecordWriter writer = null;
    UUID after = new UUID(0, 0);
    while (true) {
      UUID cursor = after;
      List<Map<String, Object>> page =
          readOnly.execute(
              s ->
                  jdbc.query(
                      "SELECT d.id, d.file_name, d.created_at, a.name FROM documents d"
                          + " JOIN assets a ON a.id = d.library_id"
                          + " WHERE d.uploaded_by_user_id = ? AND d.organization_id = ?"
                          + " AND d.id > ? AND NOT EXISTS ("
                          + PRIVATE_LIBRARY
                          + ") ORDER BY d.id LIMIT ?",
                      (rs, i) -> {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("fileName", rs.getString("file_name"));
                        record.put("createdAt", rs.getTimestamp("created_at").toInstant());
                        record.put("library", rs.getString("name"));
                        record.put("_id", rs.getObject("id", UUID.class));
                        return record;
                      },
                      subject.accountId(),
                      subject.organizationId(),
                      cursor,
                      subject.accountId(),
                      PAGE_SIZE));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section("uploads");
      }
      for (Map<String, Object> record : page) {
        after = (UUID) record.remove("_id");
        writer.write(record);
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  private void writePrivateLibraries(SelfDisclosureSubject subject, SelfDisclosureSink sink)
      throws IOException {
    List<UUID> libraries =
        readOnly.execute(
            s ->
                jdbc.queryForList(
                    "SELECT id FROM assets WHERE owner_only AND owner_user_id = ?"
                        + " AND organization_id = ? ORDER BY created_at, id",
                    UUID.class,
                    subject.accountId(),
                    subject.organizationId()));
    RecordWriter writer = null;
    for (UUID library : libraries == null ? List.<UUID>of() : libraries) {
      Instant afterCreated = Instant.EPOCH.minusSeconds(1);
      UUID afterId = new UUID(0, 0);
      while (true) {
        Instant cursorTime = afterCreated;
        UUID cursorId = afterId;
        List<Map<String, Object>> page =
            readOnly.execute(s -> privatePage(subject, library, cursorTime, cursorId));
        if (page == null || page.isEmpty()) {
          break;
        }
        if (writer == null) {
          writer = sink.section("privateLibraries");
        }
        for (Map<String, Object> record : page) {
          afterCreated = (Instant) record.get("createdAt");
          afterId = (UUID) record.remove("_id");
          writer.write(record);
        }
        if (page.size() < PAGE_SIZE) {
          break;
        }
      }
    }
  }

  private List<Map<String, Object>> privatePage(
      SelfDisclosureSubject subject, UUID library, Instant afterCreated, UUID afterId) {
    List<Map<String, Object>> page = new ArrayList<>();
    jdbc.query(
        "SELECT d.id, d.file_name, d.created_at, d.file_path, d.source_hierarchy_path,"
            + " d.source_type, d.uploaded_by_user_id, a.name FROM documents d"
            + " JOIN assets a ON a.id = d.library_id"
            + " WHERE d.library_id = ? AND d.organization_id = ? AND a.owner_only"
            + " AND a.owner_user_id = ? AND (d.created_at, d.id) > (?, ?)"
            + " ORDER BY d.created_at, d.id LIMIT ?",
        rs -> {
          boolean upload = SourceType.UPLOAD.key().equals(rs.getString("source_type"));
          Map<String, Object> record = new LinkedHashMap<>();
          record.put("fileName", rs.getString("file_name"));
          record.put("createdAt", rs.getTimestamp("created_at").toInstant());
          record.put("library", rs.getString("name"));
          record.put("path", upload ? null : rs.getString("file_path"));
          record.put("folder", rs.getString("source_hierarchy_path"));
          record.put(
              "uploadedByYou",
              subject.accountId().equals(rs.getObject("uploaded_by_user_id", UUID.class)));
          record.put("_id", rs.getObject("id", UUID.class));
          page.add(record);
        },
        library,
        subject.organizationId(),
        subject.accountId(),
        Timestamp.from(afterCreated),
        afterId,
        PAGE_SIZE);
    return page;
  }
}
