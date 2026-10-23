package io.opaa.account;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code local-sessions-and-links}: the person's sessions (start, expiry, revocation
 * with its reason) and her single-use links (purpose, creation, expiry, redemption, reason and
 * provider of a handover). Never a token hash, family or chain id; revoked access tokens stay out,
 * their revocation stands at the session. Read in pages of {@link #PAGE_SIZE}, each in a short
 * read-only transaction.
 */
@Component
public class LocalSessionsSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "local-sessions-and-links",
          "Sitzungen und Links",
          List.of(
              new Section(
                  "sessions",
                  "Ihre Sitzungen",
                  List.of(
                      Field.of("issuedAt", "Beginn", "local_refresh_tokens.issued_at"),
                      Field.of("expiresAt", "Ablauf", "local_refresh_tokens.expires_at"),
                      Field.of(
                          "sessionEndsAt",
                          "Sitzungsende spätestens",
                          "local_refresh_tokens.family_expires_at"),
                      Field.of("revokedAt", "Widerrufen", "local_refresh_tokens.revoked_at"),
                      Field.of(
                          "revocationReason",
                          "Grund des Widerrufs",
                          "local_refresh_tokens.revocation_reason"))),
              new Section(
                  "links",
                  "Ihre Einladungs- und Rücksetzlinks",
                  List.of(
                      Field.of("purpose", "Zweck", "local_action_tokens.purpose"),
                      Field.of("createdAt", "Angelegt", "local_action_tokens.created_at"),
                      Field.of("expiresAt", "Ablauf", "local_action_tokens.expires_at"),
                      Field.of("consumedAt", "Eingelöst", "local_action_tokens.consumed_at"),
                      Field.of("reason", "Anlass", "local_action_tokens.reason"),
                      Field.of("providerId", "Anbieter", "local_action_tokens.provider_id")))));

  static final int PAGE_SIZE = 500;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public LocalSessionsSelfDisclosureExporter(
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
    pages(
        sink,
        "sessions",
        "SELECT t.id, t.issued_at AS cursor_at, t.expires_at, t.family_expires_at, t.revoked_at,"
            + " t.revocation_reason FROM local_refresh_tokens t JOIN users u ON u.id = t.user_id"
            + " WHERE t.user_id = ? AND u.organization_id = ? AND (t.issued_at, t.id) > (?, ?)"
            + " ORDER BY t.issued_at, t.id LIMIT ?",
        (rs, i) -> {
          Map<String, Object> record = cursor(rs);
          record.put("issuedAt", instant(rs.getTimestamp("cursor_at")));
          record.put("expiresAt", instant(rs.getTimestamp("expires_at")));
          record.put("sessionEndsAt", instant(rs.getTimestamp("family_expires_at")));
          record.put("revokedAt", instant(rs.getTimestamp("revoked_at")));
          record.put("revocationReason", rs.getString("revocation_reason"));
          return record;
        },
        subject);
    pages(
        sink,
        "links",
        "SELECT t.id, t.created_at AS cursor_at, t.purpose, t.expires_at, t.consumed_at, t.reason,"
            + " t.provider_id FROM local_action_tokens t JOIN users u ON u.id = t.user_id"
            + " WHERE t.user_id = ? AND u.organization_id = ? AND (t.created_at, t.id) > (?, ?)"
            + " ORDER BY t.created_at, t.id LIMIT ?",
        (rs, i) -> {
          Map<String, Object> record = cursor(rs);
          record.put("purpose", rs.getString("purpose"));
          record.put("createdAt", instant(rs.getTimestamp("cursor_at")));
          record.put("expiresAt", instant(rs.getTimestamp("expires_at")));
          record.put("consumedAt", instant(rs.getTimestamp("consumed_at")));
          record.put("reason", rs.getString("reason"));
          record.put("providerId", rs.getObject("provider_id", UUID.class));
          return record;
        },
        subject);
  }

  /** Writes the rows of {@code sql} page by page; its parameters are person, cursor and limit. */
  private void pages(
      SelfDisclosureSink sink,
      String section,
      String sql,
      RowMapper<Map<String, Object>> mapper,
      SelfDisclosureSubject subject)
      throws IOException {
    RecordWriter writer = null;
    Instant afterTime = Instant.EPOCH.minusSeconds(1);
    UUID afterId = new UUID(0, 0);
    while (true) {
      Instant cursorTime = afterTime;
      UUID cursorId = afterId;
      List<Map<String, Object>> page =
          readOnly.execute(
              status ->
                  jdbc.query(
                      sql,
                      mapper,
                      subject.accountId(),
                      subject.organizationId(),
                      Timestamp.from(cursorTime),
                      cursorId,
                      PAGE_SIZE));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section(section);
      }
      for (Map<String, Object> row : page) {
        afterTime = (Instant) row.remove("_cursorAt");
        afterId = (UUID) row.remove("_id");
        writer.write(row);
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  private static Map<String, Object> cursor(ResultSet rs) throws SQLException {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("_cursorAt", rs.getTimestamp("cursor_at").toInstant());
    record.put("_id", rs.getObject("id", UUID.class));
    return record;
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
