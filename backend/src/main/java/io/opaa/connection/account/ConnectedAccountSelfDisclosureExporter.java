package io.opaa.connection.account;

import static io.opaa.common.SelfDisclosureSectionReader.instant;
import static io.opaa.common.SelfDisclosureSectionReader.keyed;
import static io.opaa.common.SelfDisclosureSectionReader.who;

import io.opaa.common.SelfDisclosureSectionReader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code connected-accounts} of the self-disclosure: the person's connected accounts
 * with their name at the provider, and the libraries she connected or answers for. No secret, no
 * token, no pending authorization goes out. The account name of a library goes out only when she
 * connected it; another connecting or responsible person is "andere Person", a responsible group
 * its name.
 */
@Component
public class ConnectedAccountSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "connected-accounts",
          "Verbundene Konten",
          List.of(
              new Section(
                  "accounts",
                  "Ihre verbundenen Konten",
                  List.of(
                      Field.of(
                          "profile",
                          "Zugang",
                          "connected_accounts.profile_id",
                          "connection_profiles.name"),
                      Field.of("state", "Zustand", "connected_accounts.state"),
                      Field.of(
                          "accountLabel",
                          "Konto beim Anbieter",
                          "connected_accounts.account_label_ciphertext"),
                      Field.of("connectedAt", "Verbunden", "connected_accounts.connected_at"),
                      Field.of(
                          "reconnectedAt", "Erneut verbunden", "connected_accounts.reconnected_at"),
                      Field.of("expiredAt", "Abgelaufen", "connected_accounts.expired_at"),
                      Field.of("lastUsedAt", "Zuletzt genutzt", "connected_accounts.last_used_at"),
                      Field.of("endedCause", "Endegrund", "connected_accounts.ended_cause"))),
              new Section(
                  "libraries",
                  "Bibliotheken, die Sie verbunden haben oder verantworten",
                  List.of(
                      Field.of(
                          "library", "Bibliothek", "library_connections.library_id", "assets.name"),
                      Field.of(
                          "profile",
                          "Zugang",
                          "library_connections.profile_id",
                          "connection_profiles.name"),
                      Field.of(
                          "accountLabel",
                          "Konto beim Anbieter",
                          "library_connections.account_label"),
                      Field.derived(
                          "connectedBy", "Verbunden von", "library_connections.connected_by"),
                      Field.of("connectedAt", "Verbunden", "library_connections.connected_at"),
                      Field.of(
                          "responsibleType",
                          "Art der Verantwortung",
                          "library_connections.responsible_type"),
                      Field.derived(
                          "responsible", "Verantwortlich", "library_connections.responsible_id"),
                      Field.of(
                          "responsibleGroup",
                          "Verantwortliche Gruppe",
                          "library_connections.responsible_id",
                          "groups.name"),
                      Field.of("endedCause", "Endegrund", "library_connections.ended_cause"),
                      Field.of("endedAt", "Beendet", "library_connections.ended_at")))));

  private final JdbcTemplate jdbc;
  private final CredentialsEncryptor encryptor;
  private final SelfDisclosureSectionReader reader;

  public ConnectedAccountSelfDisclosureExporter(
      JdbcTemplate jdbc,
      CredentialsEncryptor encryptor,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.encryptor = encryptor;
    this.reader = new SelfDisclosureSectionReader(transactionManager);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    UUID me = subject.accountId();
    reader.copy(
        sink,
        "accounts",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT c.id, p.name AS profile_name, c.state, c.account_label_ciphertext,"
                    + " c.connected_at, c.reconnected_at, c.expired_at, c.last_used_at,"
                    + " c.ended_cause FROM connected_accounts c"
                    + " JOIN connection_profiles p ON p.id = c.profile_id"
                    + " WHERE c.user_id = ? AND c.organization_id = ?"
                    + " AND (c.connected_at, c.id) > (?, ?) ORDER BY c.connected_at, c.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "connected_at", "id");
                  record.put("profile", rs.getString("profile_name"));
                  record.put("state", rs.getString("state"));
                  record.put(
                      "accountLabel",
                      label(
                          rs.getString("account_label_ciphertext"),
                          rs.getObject("id", UUID.class)));
                  record.put("connectedAt", instant(rs.getTimestamp("connected_at")));
                  record.put("reconnectedAt", instant(rs.getTimestamp("reconnected_at")));
                  record.put("expiredAt", instant(rs.getTimestamp("expired_at")));
                  record.put("lastUsedAt", instant(rs.getTimestamp("last_used_at")));
                  record.put("endedCause", rs.getString("ended_cause"));
                  return record;
                },
                me,
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "libraries",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT l.library_id, a.name AS library_name, p.name AS profile_name,"
                    + " l.account_label, l.connected_by, l.connected_at, l.responsible_type,"
                    + " l.responsible_id, g.name AS group_name, l.ended_cause, l.ended_at,"
                    + " l.created_at FROM library_connections l"
                    + " JOIN assets a ON a.id = l.library_id"
                    + " LEFT JOIN connection_profiles p ON p.id = l.profile_id"
                    + " LEFT JOIN groups g ON l.responsible_type = 'GROUP'"
                    + " AND g.id = l.responsible_id"
                    + " WHERE a.organization_id = ? AND (l.connected_by = ?"
                    + " OR (l.responsible_type = 'USER' AND l.responsible_id = ?))"
                    + " AND (l.created_at, l.library_id) > (?, ?)"
                    + " ORDER BY l.created_at, l.library_id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "library_id");
                  UUID connectedBy = rs.getObject("connected_by", UUID.class);
                  boolean user = "USER".equals(rs.getString("responsible_type"));
                  record.put("library", rs.getString("library_name"));
                  record.put("profile", rs.getString("profile_name"));
                  record.put(
                      "accountLabel",
                      me.equals(connectedBy) ? rs.getString("account_label") : null);
                  record.put("connectedBy", who(connectedBy, me, null));
                  record.put("connectedAt", instant(rs.getTimestamp("connected_at")));
                  record.put("responsibleType", rs.getString("responsible_type"));
                  record.put(
                      "responsible",
                      user ? who(rs.getObject("responsible_id", UUID.class), me, null) : null);
                  record.put("responsibleGroup", user ? null : rs.getString("group_name"));
                  record.put("endedCause", rs.getString("ended_cause"));
                  record.put("endedAt", instant(rs.getTimestamp("ended_at")));
                  return record;
                },
                subject.organizationId(),
                me,
                me,
                afterTime,
                afterId,
                limit));
  }

  /** The account name for its owner; a value that is not encrypted or not readable is none. */
  private String label(String ciphertext, UUID accountId) {
    if (!CredentialsEncryptor.isEncrypted(ciphertext)) {
      return null;
    }
    try {
      return encryptor.decrypt(ciphertext, ConnectedAccount.ACCOUNT_LABEL.row(accountId));
    } catch (CredentialsEncryptionKeyMissingException e) {
      return null;
    }
  }
}
