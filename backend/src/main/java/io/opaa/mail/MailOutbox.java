package io.opaa.mail;

import io.opaa.security.SettingsEncryptor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The table {@code mail_outbox} (ADR-0053, Entscheidung 11): rendered mail waiting for delivery,
 * recipient and content encrypted with the settings key because a link in it is a one-time secret.
 *
 * <p>Every time comparison runs on the database clock ({@code now()} in the statement), so workers
 * on several instances agree on what is due and whose claim has lapsed. A claim moves {@code
 * next_attempt_at} past the lease: until then no other worker picks the row up, after it any worker
 * may - a worker that died mid-delivery leaves the row to the next one.
 *
 * <p>Writes join the caller's transaction when there is one; a rollback discards the mail with the
 * act it announced.
 */
@Component
class MailOutbox {

  private final JdbcTemplate jdbc;
  private final SettingsEncryptor encryptor;

  MailOutbox(JdbcTemplate jdbc, SettingsEncryptor encryptor) {
    this.jdbc = jdbc;
    this.encryptor = encryptor;
  }

  /** A claimed row, decrypted; {@code attempts} counts this attempt. */
  record Claimed(UUID id, MailTemplateKey key, String recipient, RenderedMail mail, int attempts) {

    /** Stable across every attempt of this row, so a duplicate delivery is recognisable. */
    String messageId() {
      return "<" + id + "@opaa>";
    }

    @Override
    public String toString() {
      return "Claimed[id=" + id + ", key=" + key.key() + ", attempts=" + attempts + "]";
    }
  }

  /** A claimed row whose content could not be read back (wrong or missing key). */
  record Unreadable(UUID id, String templateKey, int attempts, String reason) {}

  /** What one claim returned: the rows to deliver and the ones to give up at once. */
  record Batch(List<Claimed> claimed, List<Unreadable> unreadable) {
    boolean isEmpty() {
      return claimed.isEmpty() && unreadable.isEmpty();
    }
  }

  /**
   * Writes {@code mail} for {@code recipient}, due at once.
   *
   * @throws IllegalStateException when the settings key is missing or invalid; nothing is written
   */
  UUID insert(MailTemplateKey key, String recipient, RenderedMail mail) {
    UUID id = UUID.randomUUID();
    String recipientCiphertext = encryptor.encrypt(recipient);
    String subjectCiphertext = encryptor.encrypt(mail.subject());
    String plainCiphertext = encryptor.encrypt(mail.bodyPlain());
    String htmlCiphertext = mail.hasHtml() ? encryptor.encrypt(mail.bodyHtml()) : null;
    jdbc.update(
        "INSERT INTO mail_outbox (id, template_key, recipient_ciphertext, subject_ciphertext,"
            + " body_plain_ciphertext, body_html_ciphertext) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        key.key(),
        recipientCiphertext,
        subjectCiphertext,
        plainCiphertext,
        htmlCiphertext);
    return id;
  }

  /**
   * Claims up to {@code limit} due rows for {@code lease}, oldest first. Rows another worker holds
   * locked are skipped, not waited for; the claim is committed by the statement itself.
   */
  Batch claimDue(int limit, Duration lease) {
    List<Row> rows =
        jdbc.query(
            "UPDATE mail_outbox SET attempts = attempts + 1,"
                + " next_attempt_at = now() + make_interval(secs => ?)"
                + " WHERE id IN (SELECT id FROM mail_outbox WHERE next_attempt_at <= now()"
                + " ORDER BY next_attempt_at, created_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                + " RETURNING id, template_key, recipient_ciphertext, subject_ciphertext,"
                + " body_plain_ciphertext, body_html_ciphertext, attempts, created_at",
            MailOutbox::row,
            (double) lease.toMillis() / 1000.0,
            limit);
    List<Claimed> claimed = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    for (Row row : rows.stream().sorted(Comparator.comparing(Row::createdAt)).toList()) {
      try {
        claimed.add(decrypt(row));
      } catch (RuntimeException e) {
        unreadable.add(new Unreadable(row.id(), row.templateKey(), row.attempts(), e.getMessage()));
      }
    }
    return new Batch(List.copyOf(claimed), List.copyOf(unreadable));
  }

  /** Removes the row: delivered, or given up. */
  void delete(UUID id) {
    jdbc.update("DELETE FROM mail_outbox WHERE id = ?", id);
  }

  /**
   * Makes the row due again after {@code delay}, with the masked reason of the failed attempt -
   * only while the claim is still this attempt's: a worker that took over after the lease decides.
   */
  void reschedule(UUID id, int attempts, Duration delay, String maskedReason) {
    jdbc.update(
        "UPDATE mail_outbox SET next_attempt_at = now() + make_interval(secs => ?),"
            + " last_failure_reason = ? WHERE id = ? AND attempts = ?",
        (double) delay.toMillis() / 1000.0,
        truncated(maskedReason),
        id,
        attempts);
  }

  /** How many mails wait, delivered or not yet attempted. */
  long pending() {
    Long count = jdbc.queryForObject("SELECT count(*) FROM mail_outbox", Long.class);
    return count == null ? 0 : count;
  }

  private Claimed decrypt(Row row) {
    MailTemplateKey key =
        MailTemplateKey.fromKey(row.templateKey())
            .orElseThrow(() -> new IllegalStateException("Unknown template " + row.templateKey()));
    RenderedMail mail =
        new RenderedMail(
            encryptor.decrypt(row.subjectCiphertext()),
            encryptor.decrypt(row.plainCiphertext()),
            encryptor.decrypt(row.htmlCiphertext()));
    return new Claimed(
        row.id(), key, encryptor.decrypt(row.recipientCiphertext()), mail, row.attempts());
  }

  private static String truncated(String reason) {
    if (reason == null || reason.length() <= 1000) {
      return reason;
    }
    return reason.substring(0, 1000);
  }

  private record Row(
      UUID id,
      String templateKey,
      String recipientCiphertext,
      String subjectCiphertext,
      String plainCiphertext,
      String htmlCiphertext,
      int attempts,
      Instant createdAt) {}

  private static Row row(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("template_key"),
        rs.getString("recipient_ciphertext"),
        rs.getString("subject_ciphertext"),
        rs.getString("body_plain_ciphertext"),
        rs.getString("body_html_ciphertext"),
        rs.getInt("attempts"),
        rs.getTimestamp("created_at").toInstant());
  }
}
