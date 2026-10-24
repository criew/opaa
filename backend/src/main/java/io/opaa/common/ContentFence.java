package io.opaa.common;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A fence around text a document controls, as handed to a model: an opening and a closing marker
 * with the tag and an id fresh per use. {@link #strip} removes the tag's keyword itself from such
 * text - in any case, in fullwidth letters and with invisible format characters between its letters
 * - and repeats until nothing changes, so no text can contain a marker of the fence, also not one a
 * removal or a later cut would reassemble. Whatever else of a marker remains is plain text.
 */
public final class ContentFence {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** ASCII letter to fullwidth letter: {@code a} is U+FF41, {@code A} is U+FF21. */
  private static final int FULLWIDTH_OFFSET = 0xFF41 - 'a';

  private final String tag;
  private final Pattern keyword;

  private ContentFence(String tag) {
    this.tag = tag;
    this.keyword =
        Pattern.compile(
            tag.chars().mapToObj(ContentFence::anySpelling).collect(Collectors.joining("\\p{Cf}*")),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  /** A fence whose markers carry {@code tag}, lower-case ASCII letters only. */
  public static ContentFence of(String tag) {
    if (!tag.matches("[a-z]+")) {
      throw new IllegalArgumentException("A fence tag consists of lower-case letters: " + tag);
    }
    return new ContentFence(tag);
  }

  /** A fresh id: 16 hex characters from a secure random source. */
  public static String newId() {
    byte[] bytes = new byte[8];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  public String open(String id) {
    return "<" + tag + " kennung=\"" + id + "\">";
  }

  public String close(String id) {
    return "</" + tag + " kennung=\"" + id + "\">";
  }

  /**
   * {@code text} stripped and between the markers of {@code id}; {@code null} stays {@code null}.
   */
  public String wrap(String id, String text) {
    return text == null ? null : open(id) + "\n" + strip(text) + "\n" + close(id);
  }

  /** {@code text} without the keyword of the tag; {@code null} stays {@code null}. */
  public String strip(String text) {
    if (text == null) {
      return null;
    }
    String previous;
    String current = text;
    do {
      previous = current;
      current = keyword.matcher(previous).replaceAll("");
    } while (!current.equals(previous));
    return current;
  }

  private static String anySpelling(int letter) {
    return "[" + (char) letter + new String(Character.toChars(letter + FULLWIDTH_OFFSET)) + "]";
  }
}
