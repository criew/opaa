package io.opaa.auth;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code accounts}: the person's own {@code users} row - identity at the provider,
 * address, display name, last sign-in, system role, lock by the directory sync and creation.
 */
@Component
public class AccountSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "accounts",
          "Benutzerkonto",
          List.of(
              new Section(
                  "account",
                  "Ihr Konto",
                  List.of(
                      Field.of("subject", "Kennung beim Anbieter", "users.subject"),
                      Field.of("issuer", "Anbieter", "users.issuer"),
                      Field.of("email", "E-Mail-Adresse", "users.email"),
                      Field.of("displayName", "Anzeigename", "users.display_name"),
                      Field.of("lastLoginAt", "Letzte Anmeldung", "users.last_login_at"),
                      Field.of("systemRole", "Systemrolle", "users.system_role"),
                      Field.of(
                          "directoryLockedAt",
                          "Gesperrt durch den Verzeichnisabgleich seit",
                          "users.directory_locked_at"),
                      Field.of("createdAt", "Angelegt", "users.created_at")))));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public AccountSelfDisclosureExporter(
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
    List<Map<String, Object>> rows =
        readOnly.execute(
            status ->
                jdbc.query(
                    "SELECT subject, issuer, email, display_name, last_login_at, system_role,"
                        + " directory_locked_at, created_at FROM users"
                        + " WHERE id = ? AND organization_id = ?",
                    (rs, i) -> {
                      Map<String, Object> record = new LinkedHashMap<>();
                      record.put("subject", rs.getString("subject"));
                      record.put("issuer", rs.getString("issuer"));
                      record.put("email", rs.getString("email"));
                      record.put("displayName", rs.getString("display_name"));
                      record.put("lastLoginAt", instant(rs.getTimestamp("last_login_at")));
                      record.put("systemRole", rs.getString("system_role"));
                      record.put(
                          "directoryLockedAt", instant(rs.getTimestamp("directory_locked_at")));
                      record.put("createdAt", instant(rs.getTimestamp("created_at")));
                      return record;
                    },
                    subject.accountId(),
                    subject.organizationId()));
    if (rows == null || rows.isEmpty()) {
      return;
    }
    SelfDisclosureSink.RecordWriter writer = sink.section("account");
    for (Map<String, Object> row : rows) {
      writer.write(row);
    }
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
