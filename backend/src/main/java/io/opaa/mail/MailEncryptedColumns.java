package io.opaa.mail;

import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/** The columns of the mail package encrypted with the settings key (ADR-0064). */
@Component
class MailEncryptedColumns implements EncryptedColumns {

  static final EncryptedColumn SETTINGS_PASSWORD =
      EncryptedColumn.settings("mail_settings", "password_ciphertext");
  static final EncryptedColumn OUTBOX_RECIPIENT =
      EncryptedColumn.settings("mail_outbox", "recipient_ciphertext");
  static final EncryptedColumn OUTBOX_SUBJECT =
      EncryptedColumn.settings("mail_outbox", "subject_ciphertext");
  static final EncryptedColumn OUTBOX_BODY_PLAIN =
      EncryptedColumn.settings("mail_outbox", "body_plain_ciphertext");
  static final EncryptedColumn OUTBOX_BODY_HTML =
      EncryptedColumn.settings("mail_outbox", "body_html_ciphertext");

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(
        SETTINGS_PASSWORD, OUTBOX_RECIPIENT, OUTBOX_SUBJECT, OUTBOX_BODY_PLAIN, OUTBOX_BODY_HTML);
  }
}
