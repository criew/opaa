package io.opaa.mail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps recipient addresses out of the application log, out of {@code
 * mail_settings.last_failure_reason} and out of every API response (#1536, ADR-0033 Entscheidung
 * 10): the local part is reduced to its first character, the domain stays.
 */
final class MailRecipients {

  /**
   * Anything with an {@code @} between two runs of non-delimiter characters - deliberately wider
   * than a valid address, because this also runs over SMTP server messages where an address appears
   * inside angle brackets or a sentence.
   */
  private static final Pattern ADDRESS_LIKE = Pattern.compile("[^\\s<>,;:\"]+@[^\\s<>,;:\"]+");

  private MailRecipients() {}

  /**
   * {@code e***@amt.example} for one address; {@code -} for none, {@code ***} for a non-address.
   */
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

  /**
   * Shortens every address-looking token in {@code text}. The last barrier before a failure cause
   * is logged, stored or answered: an SMTP rejection quotes the address it rejected, and that text
   * is otherwise passed on verbatim.
   */
  static String maskAddresses(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    Matcher matcher = ADDRESS_LIKE.matcher(text);
    StringBuilder masked = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(masked, Matcher.quoteReplacement(shortened(matcher.group())));
    }
    matcher.appendTail(masked);
    return masked.toString();
  }
}
