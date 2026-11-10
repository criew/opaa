package io.opaa.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The one way text that a person, a document or an earlier answer controls enters a model prompt.
 * {@link #clean} removes invisible characters - control and format characters such as tag
 * characters (U+E0000-E007F), zero-width and direction controls - and then every given marker,
 * repeated until nothing changes, so no removal can reassemble a marker. {@link #between} sets the
 * cleaned text between a start and an end marker, {@link ContentFence#wrap} between the markers of
 * a fence with a fresh id. Tab and line breaks stay; a zero-width joiner stays only inside an
 * emoji.
 */
public final class PromptText {

  /** Control and format characters except tab, line break and the joiner inside emoji. */
  private static final Pattern INVISIBLE =
      Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]|[\\p{Cf}&&[^\\u200D]]");

  private static final int JOINER = 0x200D;

  private PromptText() {}

  /** {@code text} without invisible characters and markers; {@code null} stays {@code null}. */
  public static String clean(String text, String... markers) {
    return clean(
        text, List.of(markers).stream().map(m -> Pattern.compile(Pattern.quote(m))).toList());
  }

  /**
   * {@code text} without invisible characters and without any match of {@code markers}, repeated
   * until nothing changes; {@code null} stays {@code null}.
   */
  public static String clean(String text, List<Pattern> markers) {
    if (text == null) {
      return null;
    }
    String previous;
    String current = text;
    do {
      previous = current;
      current = withoutInvisible(current);
      for (Pattern marker : markers) {
        current = marker.matcher(current).replaceAll("");
      }
    } while (!current.equals(previous));
    return current;
  }

  /**
   * The cleaned, stripped {@code text} on its own lines between {@code start} and {@code end},
   * neither of which it can contain.
   */
  public static String between(String start, String end, String text) {
    return start + "\n" + clean(text, start, end).strip() + "\n" + end;
  }

  /** {@code text} without invisible characters; {@code null} stays {@code null}. */
  public static String withoutInvisible(String text) {
    if (text == null) {
      return null;
    }
    return withoutStrayJoiners(INVISIBLE.matcher(text).replaceAll(""));
  }

  /**
   * Keeps a joiner only between two symbols, where it joins an emoji; anywhere else it only hides a
   * word from a check. Works on code points, since emoji lie outside the BMP.
   */
  private static String withoutStrayJoiners(String text) {
    if (text.indexOf(JOINER) < 0) {
      return text;
    }
    int[] codePoints = text.codePoints().toArray();
    StringBuilder kept = new StringBuilder(text.length());
    for (int i = 0; i < codePoints.length; i++) {
      int cp = codePoints[i];
      if (cp == JOINER
          && !(i > 0
              && i + 1 < codePoints.length
              && joinsAfter(codePoints[i - 1])
              && Character.getType(codePoints[i + 1]) == Character.OTHER_SYMBOL)) {
        continue;
      }
      kept.appendCodePoint(cp);
    }
    return kept.toString();
  }

  /** A symbol, a skin-tone modifier or the emoji presentation selector before a joiner. */
  private static boolean joinsAfter(int cp) {
    int type = Character.getType(cp);
    return type == Character.OTHER_SYMBOL || type == Character.MODIFIER_SYMBOL || cp == 0xFE0F;
  }
}
