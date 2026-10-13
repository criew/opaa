package io.opaa.llm;

import java.util.List;

/**
 * Reasoning text some OpenAI-compatible servers put into the answer itself: {@code
 * <think>…</think>} or {@code <thought>…</thought>} blocks at its very start, or - when the chat
 * template already put {@code <think>} into the prompt - everything before a lone closing {@code
 * </think>}. That closing tag counts only as its first occurrence, at the start of a line, with no
 * opening tag and no open code fence before it; every other tag is answer text and stays untouched.
 */
public final class LeadingReasoningText {

  private static final List<String> TAGS = List.of("think", "thought");

  /** Only {@code think} is ever prefilled by a chat template; {@code thought} always opens. */
  private static final String LONE_CLOSING_TAG = "</think>";

  private static final String CODE_FENCE = "```";

  private LeadingReasoningText() {}

  /**
   * The answer without its leading reasoning text and the whitespace around it. A leading block
   * that is never closed - the output limit cut the model off while reasoning - leaves no answer at
   * all; reasoning without an opening tag that is cut off stays, as nothing marks it.
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
      int loneClosingTag = loneClosingTagAt(text);
      if (loneClosingTag < 0) {
        return new Stripped(text, 0, false);
      }
      position = loneClosingTag + LONE_CLOSING_TAG.length();
    }
    int answerStart = skipWhitespace(text, position);
    return new Stripped(text.substring(answerStart), answerStart, false);
  }

  /** Whether {@code text} opens with a reasoning block, closed or not. */
  public static boolean startsWithBlock(String text) {
    return text != null && openingTagAt(text, skipWhitespace(text, 0)) != null;
  }

  /** Where the reasoning ends in an answer whose opening tag was in the prompt, or -1. */
  private static int loneClosingTagAt(String text) {
    int index = text.indexOf(LONE_CLOSING_TAG);
    if (index < 0 || !startsLine(text, index)) {
      return -1;
    }
    String before = text.substring(0, index);
    for (String tag : TAGS) {
      if (before.contains("<" + tag + ">")) {
        return -1;
      }
    }
    return occurrences(before, CODE_FENCE) % 2 == 0 ? index : -1;
  }

  private static boolean startsLine(String text, int index) {
    int i = index - 1;
    while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
      i--;
    }
    return i < 0 || text.charAt(i) == '\n';
  }

  private static int occurrences(String text, String part) {
    int count = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
      count++;
    }
    return count;
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
