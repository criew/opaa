package io.opaa.llm;

import java.util.List;

/**
 * Reasoning text some OpenAI-compatible servers put into the answer itself: {@code
 * <think>…</think>} or {@code <thought>…</thought>} blocks at its very start. Only leading blocks
 * count; the same tags later in the text are answer text and stay untouched.
 */
public final class LeadingReasoningText {

  private static final List<String> TAGS = List.of("think", "thought");

  private LeadingReasoningText() {}

  /**
   * The answer without its leading blocks and the whitespace around them. A leading block that is
   * never closed - the output limit cut the model off while reasoning - leaves no answer at all.
   */
  public static Stripped strip(String text) {
    if (text == null) {
      return new Stripped(null, 0, false);
    }
    int position = 0;
    while (true) {
      int start = skipWhitespace(text, position);
      String tag = openingTagAt(text, start);
      if (tag == null) {
        break;
      }
      String closingTag = "</" + tag + ">";
      int end = text.indexOf(closingTag, start + tag.length() + 2);
      if (end < 0) {
        return new Stripped("", text.length(), true);
      }
      position = end + closingTag.length();
    }
    if (position == 0) {
      return new Stripped(text, 0, false);
    }
    int answerStart = skipWhitespace(text, position);
    return new Stripped(text.substring(answerStart), answerStart, false);
  }

  /** Whether {@code text} opens with a reasoning block, closed or not. */
  public static boolean startsWithBlock(String text) {
    return text != null && openingTagAt(text, skipWhitespace(text, 0)) != null;
  }

  private static String openingTagAt(String text, int index) {
    for (String tag : TAGS) {
      if (text.startsWith("<" + tag + ">", index)) {
        return tag;
      }
    }
    return null;
  }

  private static int skipWhitespace(String text, int index) {
    int i = index;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * @param text the answer, {@code null} only for a {@code null} input
   * @param removedCharacters how much leading text was dropped
   * @param unclosed whether a leading block had no closing tag
   */
  public record Stripped(String text, int removedCharacters, boolean unclosed) {}
}
