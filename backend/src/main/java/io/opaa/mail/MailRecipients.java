package io.opaa.mail;

/**
 * Shortens a recipient address for the application log (#1536, ADR-0033 Entscheidung 10): the local
 * part is reduced to its first character, the domain is kept.
 *
 * <p>The domain is what a diagnosis needs ("alle Mails an diese Domäne scheitern"); the local part
 * is the person, and a failing mail server would otherwise write one address per attempt into a log
 * that is kept and shipped elsewhere.
 */
final class MailRecipients {

  private MailRecipients() {}

  static String shortened(String address) {
    if (address == null || address.isBlank()) {
      return "-";
    }
    int at = address.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return address.charAt(0) + "***" + address.substring(at);
  }
}
