package io.opaa.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The one way text that a person, a document or an earlier answer controls enters a model prompt.
 * {@link #clean} removes invisible characters - control and format characters such as tag
 * characters (U+E0000-E007F), zero-width and direction controls, Hangul fillers, the combining
 * grapheme joiner and the supplementary variation selectors (U+E0100-E01EF) - and then every given
 * marker, repeated until nothing changes, so no removal can reassemble a marker. {@link #between}
 * sets the cleaned text between a start and an end marker, {@link ContentFence#wrap} between the
 * markers of a fence with a fresh id. Tab and line breaks stay. What writing needs stays too: a
 * zero-width non-joiner or joiner between two letters of a script shaped by them (not Latin, where
 * it would only hide a label such as "Dokument:" from a check), a joiner between two emoji symbols
 * and a single variation selector (U+FE00-FE0F) directly after the character it selects.
 */
public final class PromptText {

  /** Characters that never show and are removed wherever they stand. */
  private static final Pattern INVISIBLE =
      Pattern.compile(
          "[\\p{Cc}&&[^\\t\\n\\r]]|[\\p{Cf}&&[^\\u200C\\u200D]]"
              + "|[\\u034F\\u115F\\u1160\\u3164\\uFFA0\\x{E0100}-\\x{E01EF}]");

  private static final int NON_JOINER = 0x200C;
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
    return withoutStrayJoinersAndSelectors(INVISIBLE.matcher(text).replaceAll(""));
  }

  /**
   * Keeps a joiner, a non-joiner or a variation selector only where it shapes what is written
   * around it; anywhere else it only hides a word from a check. Works on code points, since emoji
   * lie outside the BMP; the character before is the last one kept.
   */
  private static String withoutStrayJoinersAndSelectors(String text) {
    if (text.chars().noneMatch(c -> c == NON_JOINER || c == JOINER || isSelector(c))) {
      return text;
    }
    int[] codePoints = text.codePoints().toArray();
    StringBuilder kept = new StringBuilder(text.length());
    int before = -1;
    for (int i = 0; i < codePoints.length; i++) {
      int cp = codePoints[i];
      int after = i + 1 < codePoints.length ? codePoints[i + 1] : -1;
      boolean keep =
          switch (cp) {
            case NON_JOINER -> joinsLetters(before, after);
            case JOINER -> joinsLetters(before, after) || joinsEmoji(before, after);
            default -> !isSelector(cp) || selects(before);
          };
      if (keep) {
        kept.appendCodePoint(cp);
        before = cp;
      }
    }
    return kept.toString();
  }

  private static boolean isSelector(int cp) {
    return cp >= 0xFE00 && cp <= 0xFE0F;
  }

  /** A character a variation selector can follow: none of the invisible ones, no blank. */
  private static boolean selects(int before) {
    return before >= 0
        && !isSelector(before)
        && before != JOINER
        && before != NON_JOINER
        && !Character.isWhitespace(before);
  }

  /**
   * A letter, or a letter's combining mark such as a virama, before; a letter after; neither of
   * them Latin or common to all scripts.
   */
  private static boolean joinsLetters(int before, int after) {
    if (before < 0 || after < 0) {
      return false;
    }
    int type = Character.getType(before);
    return (Character.isLetter(before)
            || type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK)
        && Character.isLetter(after)
        && shapedByJoiners(before)
        && shapedByJoiners(after);
  }

  private static boolean shapedByJoiners(int cp) {
    Character.UnicodeScript script = Character.UnicodeScript.of(cp);
    return script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON;
  }

  /** A symbol, a skin-tone modifier or the emoji presentation selector before; a symbol after. */
  private static boolean joinsEmoji(int before, int after) {
    if (before < 0 || after < 0) {
      return false;
    }
    int type = Character.getType(before);
    return (type == Character.OTHER_SYMBOL || type == Character.MODIFIER_SYMBOL || before == 0xFE0F)
        && Character.getType(after) == Character.OTHER_SYMBOL;
  }
}
