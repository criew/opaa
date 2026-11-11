package io.opaa.common;

/**
 * Header values that may carry a secret - a key, a token, a password - checked before an HTTP
 * client sees them. The JDK client refuses a value it cannot send with a message that quotes the
 * whole value, and that message reaches the log with every stack trace that carries it; this check
 * refuses the same values without naming them.
 */
public final class HeaderValues {

  private HeaderValues() {}

  /**
   * {@code value} unchanged, if an HTTP header can carry it.
   *
   * @param what names the value in the refusal, never the value itself
   * @throws IllegalArgumentException for a control character other than tab or a character above
   *     U+00FF
   */
  public static String requireSafe(String value, String what) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c > 0xFF || c == 0x7F || (c < 0x20 && c != '\t')) {
        throw new IllegalArgumentException(
            "the " + what + " contains a character an HTTP header cannot carry");
      }
    }
    return value;
  }
}
