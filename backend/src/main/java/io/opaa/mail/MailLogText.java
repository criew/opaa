package io.opaa.mail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a failure cause of the mail server for a log line: without the password the check was
 * given and without any character that starts a new line, ASCII or Unicode.
 */
final class MailLogText {

  /**
   * A password at least this long is replaced wherever it occurs; a shorter one only where it
   * stands alone, so that one or two characters do not garble every word that happens to hold them.
   */
  private static final int MIN_EMBEDDED_LENGTH = 3;

  private static final String WORD_CHARACTER = "[\\p{L}\\p{M}\\p{N}_]";

  /**
   * Control characters (C0, DEL, C1 including U+0085) and the Unicode line and paragraph breaks.
   */
  private static final Pattern LINE_BREAKING = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

  private static final String SECRET_MASK = "***";

  private MailLogText() {}

  /**
   * {@code text} with {@code secret} replaced as {@link #MIN_EMBEDDED_LENGTH} says; null or empty
   * changes nothing.
   */
  static String withoutSecret(String text, String secret) {
    if (text == null || secret == null || secret.isEmpty()) {
      return text;
    }
    if (secret.length() >= MIN_EMBEDDED_LENGTH) {
      return text.replace(secret, SECRET_MASK);
    }
    Pattern standalone =
        Pattern.compile(
            "(?<!" + WORD_CHARACTER + ")" + Pattern.quote(secret) + "(?!" + WORD_CHARACTER + ")");
    return standalone.matcher(text).replaceAll(Matcher.quoteReplacement(SECRET_MASK));
  }

  /** {@code text} with every character that could start a forged log line replaced by {@code ?}. */
  static String withoutLineBreaks(String text) {
    return text == null ? null : LINE_BREAKING.matcher(text).replaceAll("?");
  }
}
