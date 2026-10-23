package io.opaa.library;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.knowledge.SourceType;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code library-settings} of the self-disclosure: the libraries whose release for
 * external AI tools the person set, and her private libraries with their source address. The
 * address goes out without user info, query and fragment ({@link #withoutSecrets}); the stored
 * credentials never. Each section is read in pages, each page in a short read-only transaction.
 */
@Component
public class LibrarySettingsSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "library-settings",
          "Bibliotheksverwaltung",
          List.of(
              new Section(
                  "externalAccess",
                  "Freigaben für externe KI-Werkzeuge, die Sie gesetzt haben",
                  List.of(
                      Field.of("library", "Bibliothek", "assets.name"),
                      Field.of("state", "Zustand", "knowledge_libraries.external_access_state"),
                      Field.of("setAt", "Gesetzt", "knowledge_libraries.external_access_set_at"),
                      Field.of(
                          "expiresAt",
                          "Gültig bis",
                          "knowledge_libraries.external_access_expires_at"))),
              new Section(
                  "privateLibraries",
                  "Ihre privaten Bibliotheken",
                  List.of(
                      Field.of("library", "Bibliothek", "assets.name"),
                      Field.of("createdAt", "Angelegt", "assets.created_at"),
                      Field.of("sourceType", "Quelle", "knowledge_libraries.source_type"),
                      Field.of("sourceUrl", "Quelladresse", "knowledge_libraries.source_url"),
                      Field.of("sourcePath", "Quellpfad", "knowledge_libraries.source_path")))));

  static final int PAGE_SIZE = 500;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;
  private final SourceConnectorRegistry connectors;

  /** The registry arrives lazily - the connectors themselves sit on top of the asset shell. */
  public LibrarySettingsSelfDisclosureExporter(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      @Lazy SourceConnectorRegistry connectors) {
    this.jdbc = jdbc;
    this.connectors = connectors;
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    writePaged(
        sink,
        "externalAccess",
        "SELECT a.id, a.name, k.external_access_state, k.external_access_set_at,"
            + " k.external_access_expires_at FROM knowledge_libraries k JOIN assets a ON a.id ="
            + " k.id WHERE k.external_access_set_by_user_id = ? AND k.organization_id = ?"
            + " AND a.id > ? ORDER BY a.id LIMIT ?",
        subject,
        (rs, record) -> {
          record.put("library", rs.getString("name"));
          record.put("state", accessState(rs.getString("external_access_state")));
          record.put("setAt", instant(rs, "external_access_set_at"));
          record.put("expiresAt", instant(rs, "external_access_expires_at"));
        });
    writePaged(
        sink,
        "privateLibraries",
        "SELECT a.id, a.name, a.created_at, k.source_type, k.source_url, k.source_path"
            + " FROM knowledge_libraries k JOIN assets a ON a.id = k.id WHERE a.owner_only"
            + " AND a.owner_user_id = ? AND a.organization_id = ? AND a.id > ? ORDER BY a.id"
            + " LIMIT ?",
        subject,
        (rs, record) -> {
          record.put("library", rs.getString("name"));
          record.put("createdAt", instant(rs, "created_at"));
          record.put("sourceType", sourceTypeName(rs.getString("source_type")));
          record.put("sourceUrl", withoutSecrets(rs.getString("source_url")));
          record.put("sourcePath", rs.getString("source_path"));
        });
  }

  private void writePaged(
      SelfDisclosureSink sink,
      String section,
      String sql,
      SelfDisclosureSubject subject,
      RecordFiller filler)
      throws IOException {
    RecordWriter writer = null;
    UUID after = new UUID(0, 0);
    while (true) {
      UUID cursor = after;
      List<Row> page =
          readOnly.execute(
              s ->
                  jdbc.query(
                      sql,
                      (rs, i) -> {
                        Map<String, Object> record = new LinkedHashMap<>();
                        filler.fill(rs, record);
                        return new Row(rs.getObject("id", UUID.class), record);
                      },
                      subject.accountId(),
                      subject.organizationId(),
                      cursor,
                      PAGE_SIZE));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section(section);
      }
      for (Row row : page) {
        after = row.id();
        writer.write(row.record());
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  /** The German word for a release state as the library settings show it. */
  static String accessState(String state) {
    return switch (state) {
      case "ACTIVE" -> "aktiv";
      case "WITHDRAWN" -> "zurückgenommen";
      case "EXPIRED" -> "abgelaufen";
      case "SUSPENDED" -> "ausgesetzt";
      case "NEVER_SET" -> "nie gesetzt";
      default -> state;
    };
  }

  /** The display name of the connector serving {@code key}, the key itself for an unknown one. */
  private String sourceTypeName(String key) {
    if (key == null || !SourceType.isKey(key)) {
      return key;
    }
    return connectors
        .find(SourceType.of(key))
        .map(connector -> connector.descriptor().displayName())
        .orElse(key);
  }

  /**
   * {@code address} without user info, query and fragment: scheme, host, port and path only. An
   * address that does not parse keeps only what precedes a {@code ?} or {@code #}, with any {@code
   * user@} part removed.
   */
  static String withoutSecrets(String address) {
    if (address == null) {
      return null;
    }
    try {
      URI uri = new URI(address);
      if (uri.getScheme() != null && uri.getHost() != null) {
        return new URI(
                uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
            .toString();
      }
    } catch (URISyntaxException unparsable) {
      // falls through to the textual cut below
    }
    String cut = address.split("[?#]", 2)[0];
    return cut.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]*://)[^/]*@", "$1");
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  @FunctionalInterface
  private interface RecordFiller {
    void fill(ResultSet rs, Map<String, Object> record) throws SQLException;
  }

  private record Row(UUID id, Map<String, Object> record) {}
}
