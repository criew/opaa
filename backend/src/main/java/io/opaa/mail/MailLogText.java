package io.opaa.mail;

import io.opaa.common.LogText;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a failure cause of the mail server for a log line or a stored failure reason: without
 * the password of the transport and without any character that starts a new line, ASCII or Unicode.
 * Only the password as written is found, in any letter case; an encoded form (Base64, URL encoding)
 * or another Unicode normalisation of it is not.
 */
final class MailLogText {

  /**
   * A password at least this long is replaced wherever it occurs, in any letter case; a shorter one
   * only as written and where it does not continue a word, so that one or two characters do not
   * garble every word that happens to hold them.
   */
  private static final int MIN_EMBEDDED_LENGTH = 3;

  private static final String WORD_CHARACTER = "[\\p{L}\\p{M}\\p{N}_]";

  private static final Pattern WORD = Pattern.compile(WORD_CHARACTER);

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
      return Pattern.compile(Pattern.quote(secret), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
          .matcher(text)
          .replaceAll(Matcher.quoteReplacement(SECRET_MASK));
    }
    return standalone(secret).matcher(text).replaceAll(Matcher.quoteReplacement(SECRET_MASK));
  }

  /**
   * {@code secret} not continuing a word: the neighbour is checked only on a side where the secret
   * itself ends in a word character; a side that is a symbol bounds the secret by itself.
   */
  private static Pattern standalone(String secret) {
    String before = isWordCharacter(secret.codePointAt(0)) ? "(?<!" + WORD_CHARACTER + ")" : "";
    String after =
        isWordCharacter(secret.codePointBefore(secret.length()))
            ? "(?!" + WORD_CHARACTER + ")"
            : "";
    return Pattern.compile(before + Pattern.quote(secret) + after);
  }

  private static boolean isWordCharacter(int codePoint) {
    return WORD.matcher(Character.toString(codePoint)).matches();
  }

  /** {@code text} with every character that could start a forged log line replaced by {@code ?}. */
  static String withoutLineBreaks(String text) {
    return LogText.of(text);
  }
}
