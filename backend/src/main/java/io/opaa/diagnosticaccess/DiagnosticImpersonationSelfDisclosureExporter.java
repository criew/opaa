package io.opaa.diagnosticaccess;

import static io.opaa.common.SelfDisclosureSectionReader.instant;

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
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

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

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public DiagnosticImpersonationSelfDisclosureExporter(
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
    UUID person = subject.accountId();
    reader.copy(
        sink,
        "grants",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT d.id, d.holder_user_id, g.name AS scope, d.valid_from, d.valid_until,"
                    + " d.granted_by_user_id, d.granted_at, d.revoked_by_user_id, d.revoked_at"
                    + " FROM diagnostic_impersonation_grants d LEFT JOIN groups g ON g.id ="
                    + " d.scope_group_id WHERE d.organization_id = ? AND (d.holder_user_id = ?"
                    + " OR d.granted_by_user_id = ? OR d.revoked_by_user_id = ?)"
                    + " AND (d.granted_at, d.id) > (?, ?) ORDER BY d.granted_at, d.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record =
                      SelfDisclosureSectionReader.keyed(rs, "granted_at", "id");
                  record.put("holder", who(rs.getObject("holder_user_id", UUID.class), person));
                  record.put("scope", rs.getString("scope"));
                  record.put("validFrom", instant(rs.getTimestamp("valid_from")));
                  record.put("validUntil", instant(rs.getTimestamp("valid_until")));
                  record.put(
                      "grantedBy", who(rs.getObject("granted_by_user_id", UUID.class), person));
                  record.put("grantedAt", instant(rs.getTimestamp("granted_at")));
                  record.put(
                      "revokedBy", who(rs.getObject("revoked_by_user_id", UUID.class), person));
                  record.put("revokedAt", instant(rs.getTimestamp("revoked_at")));
                  return record;
                },
                subject.organizationId(),
                person,
                person,
                person,
                afterTime,
                afterId,
                limit));
  }

  private static String who(UUID account, UUID person) {
    return SelfDisclosureSectionReader.who(account, person, null);
  }
}
