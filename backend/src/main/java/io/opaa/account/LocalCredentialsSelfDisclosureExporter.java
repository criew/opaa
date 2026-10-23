package io.opaa.account;

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
 * The category {@code local-credentials}: the state of the person's local account - lock with its
 * reason, expiry, confirmed address, the creation reason (ADR-0033, Entscheidung 11) and a pending
 * password change with its cause. Never the password hash; the failed-login counter and its lockout
 * are always reset by the fresh sign-in before the export and say nothing.
 */
@Component
public class LocalCredentialsSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "local-credentials",
          "Lokales Konto",
          List.of(
              new Section(
                  "credentials",
                  "Zustand Ihres lokalen Kontos",
                  List.of(
                      Field.of("lockedAt", "Gesperrt seit", "local_credentials.locked_at"),
                      Field.of(
                          "lockedReason", "Grund der Sperre", "local_credentials.locked_reason"),
                      Field.of("expiresAt", "Ablauf", "local_credentials.expires_at"),
                      Field.of(
                          "emailVerifiedAt",
                          "Adresse bestätigt",
                          "local_credentials.email_verified_at"),
                      Field.of("createdReason", "Anlagegrund", "local_credentials.created_reason"),
                      Field.of(
                          "passwordChangeRequired",
                          "Passwortwechsel ausstehend",
                          "local_credentials.password_change_required"),
                      Field.of(
                          "passwordChangeReason",
                          "Anlass des Passwortwechsels",
                          "local_credentials.password_change_reason"),
                      Field.of("createdAt", "Angelegt", "local_credentials.created_at")))));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public LocalCredentialsSelfDisclosureExporter(
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
                    "SELECT c.locked_at, c.locked_reason, c.expires_at, c.email_verified_at,"
                        + " c.created_reason, c.password_change_required,"
                        + " c.password_change_reason, c.created_at"
                        + " FROM local_credentials c JOIN users u ON u.id = c.user_id"
                        + " WHERE c.user_id = ? AND u.organization_id = ?",
                    (rs, i) -> {
                      Map<String, Object> record = new LinkedHashMap<>();
                      record.put("lockedAt", instant(rs.getTimestamp("locked_at")));
                      record.put("lockedReason", rs.getString("locked_reason"));
                      record.put("expiresAt", instant(rs.getTimestamp("expires_at")));
                      record.put("emailVerifiedAt", instant(rs.getTimestamp("email_verified_at")));
                      record.put("createdReason", rs.getString("created_reason"));
                      record.put(
                          "passwordChangeRequired", rs.getBoolean("password_change_required"));
                      record.put("passwordChangeReason", rs.getString("password_change_reason"));
                      record.put("createdAt", instant(rs.getTimestamp("created_at")));
                      return record;
                    },
                    subject.accountId(),
                    subject.organizationId()));
    if (rows == null || rows.isEmpty()) {
      return;
    }
    SelfDisclosureSink.RecordWriter writer = sink.section("credentials");
    for (Map<String, Object> row : rows) {
      writer.write(row);
    }
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
