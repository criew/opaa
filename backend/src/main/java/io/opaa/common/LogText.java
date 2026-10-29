package io.opaa.common;

import java.util.regex.Pattern;

/**
 * Outside input (a request path, a file name, a host) as a log argument: every character that could
 * forge a line in the plain-text log or reorder what the reader sees shows as {@code ?}.
 */
public final class LogText {

  /**
   * Control characters (C0, DEL, C1 including U+0085), the Unicode line and paragraph breaks and
   * the bidirectional formatting characters.
   */
  private static final Pattern UNSAFE =
      Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

  private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

  private LogText() {}

  /**
   * {@code value} as text with every unsafe character replaced; {@code null} stays {@code null}.
   */
  public static String of(Object value) {
    return value == null ? null : UNSAFE.matcher(value.toString()).replaceAll("?");
  }

  /** Whether {@code value} carries a control character (C0, DEL or C1). */
  public static boolean hasControlCharacter(String value) {
    return value != null && CONTROL.matcher(value).find();
  }
}
