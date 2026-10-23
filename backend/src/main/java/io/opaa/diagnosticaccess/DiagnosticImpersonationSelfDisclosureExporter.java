package io.opaa.diagnosticaccess;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code diagnostic-impersonation} of the self-disclosure: every "Sicht als" authority
 * the person holds, granted or revoked, with scope, validity and revocation. Holder, granting and
 * revoking person appear only as {@link SelfDisclosureLabels#YOU} or {@link
 * SelfDisclosureLabels#OTHER_PERSON}. Read in pages, each in a short read-only transaction.
 */
@Component
public class DiagnosticImpersonationSelfDisclosureExporter implements SelfDisclosureExporter {

  private static final String TABLE = "diagnostic_impersonation_grants.";

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "diagnostic-impersonation",
          "Befugnisse „Sicht als“",
          List.of(
              new Section(
                  "grants",
                  "Befugnisse, die Sie halten oder erteilt haben",
                  List.of(
                      Field.derived("holder", "Inhaber", TABLE + "holder_user_id"),
                      Field.of("scope", "Bereich", "groups.name"),
                      Field.of("validFrom", "Gültig ab", TABLE + "valid_from"),
                      Field.of("validUntil", "Gültig bis", TABLE + "valid_until"),
                      Field.derived("grantedBy", "Erteilt von", TABLE + "granted_by_user_id"),
                      Field.of("grantedAt", "Erteilt am", TABLE + "granted_at"),
                      Field.derived("revokedBy", "Widerrufen von", TABLE + "revoked_by_user_id"),
                      Field.of("revokedAt", "Widerrufen am", TABLE + "revoked_at")))));

  static final int PAGE_SIZE = 500;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public DiagnosticImpersonationSelfDisclosureExporter(
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
    Instant afterGranted = Instant.EPOCH.minusSeconds(1);
    UUID afterId = new UUID(0, 0);
    while (true) {
      Instant cursorTime = afterGranted;
      UUID cursorId = afterId;
      List<Map<String, Object>> page = readOnly.execute(s -> page(subject, cursorTime, cursorId));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section("grants");
      }
      for (Map<String, Object> record : page) {
        afterGranted = (Instant) record.get("grantedAt");
        afterId = (UUID) record.remove("_id");
        writer.write(record);
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  private List<Map<String, Object>> page(
      SelfDisclosureSubject subject, Instant afterGranted, UUID afterId) {
    UUID person = subject.accountId();
    return jdbc.query(
        "SELECT d.id, d.holder_user_id, g.name AS scope, d.valid_from, d.valid_until,"
            + " d.granted_by_user_id, d.granted_at, d.revoked_by_user_id, d.revoked_at"
            + " FROM diagnostic_impersonation_grants d LEFT JOIN groups g ON g.id ="
            + " d.scope_group_id WHERE d.organization_id = ? AND (d.holder_user_id = ?"
            + " OR d.granted_by_user_id = ? OR d.revoked_by_user_id = ?)"
            + " AND (d.granted_at, d.id) > (?, ?) ORDER BY d.granted_at, d.id LIMIT ?",
        (rs, i) -> {
          Map<String, Object> record = new LinkedHashMap<>();
          record.put("holder", label(person, rs.getObject("holder_user_id", UUID.class)));
          record.put("scope", rs.getString("scope"));
          record.put("validFrom", instant(rs.getTimestamp("valid_from")));
          record.put("validUntil", instant(rs.getTimestamp("valid_until")));
          record.put("grantedBy", label(person, rs.getObject("granted_by_user_id", UUID.class)));
          record.put("grantedAt", instant(rs.getTimestamp("granted_at")));
          record.put("revokedBy", label(person, rs.getObject("revoked_by_user_id", UUID.class)));
          record.put("revokedAt", instant(rs.getTimestamp("revoked_at")));
          record.put("_id", rs.getObject("id", UUID.class));
          return record;
        },
        subject.organizationId(),
        person,
        person,
        person,
        Timestamp.from(afterGranted),
        afterId,
        PAGE_SIZE);
  }

  private static String label(UUID person, UUID account) {
    if (account == null) {
      return null;
    }
    return person.equals(account) ? SelfDisclosureLabels.YOU : SelfDisclosureLabels.OTHER_PERSON;
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
