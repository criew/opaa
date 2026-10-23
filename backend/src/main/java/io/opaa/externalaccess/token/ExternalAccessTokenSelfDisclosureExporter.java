package io.opaa.externalaccess.token;

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
import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code external-access-tokens} of the self-disclosure: the person's access tokens
 * for external AI tools with name, creation, expiry, last day of use and revocation with its
 * reason. Neither the token, its prefix nor its lookup hash goes out.
 */
@Component
public class ExternalAccessTokenSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "external-access-tokens",
          "Zugangstokens für externe KI-Werkzeuge",
          List.of(
              new Section(
                  "tokens",
                  "Ihre Zugangstokens",
                  List.of(
                      Field.of("name", "Name", "external_access_tokens.name"),
                      Field.of("createdAt", "Angelegt", "external_access_tokens.created_at"),
                      Field.of("expiresAt", "Gültig bis", "external_access_tokens.expires_at"),
                      Field.of(
                          "lastUsedOn",
                          "Zuletzt genutzt am",
                          "external_access_tokens.last_used_on"),
                      Field.of("revokedAt", "Widerrufen", "external_access_tokens.revoked_at"),
                      Field.of(
                          "revocationReason",
                          "Anlass des Widerrufs",
                          "external_access_tokens.revocation_reason")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public ExternalAccessTokenSelfDisclosureExporter(
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
        "tokens",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT t.id, t.name, t.created_at, t.expires_at, t.last_used_on, t.revoked_at,"
                    + " t.revocation_reason FROM external_access_tokens t"
                    + " JOIN users u ON u.id = t.user_id"
                    + " WHERE t.user_id = ? AND u.organization_id = ?"
                    + " AND (t.created_at, t.id) > (?, ?) ORDER BY t.created_at, t.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  Date lastUsedOn = rs.getDate("last_used_on");
                  record.put("name", rs.getString("name"));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
                  record.put("expiresAt", instant(rs.getTimestamp("expires_at")));
                  record.put("lastUsedOn", lastUsedOn == null ? null : lastUsedOn.toLocalDate());
                  record.put("revokedAt", instant(rs.getTimestamp("revoked_at")));
                  record.put("revocationReason", rs.getString("revocation_reason"));
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
  }
}
