package io.opaa.llm;

import java.util.List;

/**
 * Reasoning text some OpenAI-compatible servers put into the answer itself: {@code
 * <think>…</think>} or {@code <thought>…</thought>} blocks at its very start, or - when the chat
 * template already put {@code <think>} into the prompt - everything before a lone closing {@code
 * </think>}. That closing tag counts only as its first occurrence, alone on its line (indented by
 * at most three spaces), with no opening tag and no open code fence before it; every other tag is
 * answer text and stays untouched.
 */
public final class LeadingReasoningText {

  private static final List<String> TAGS = List.of("think", "thought");

  /** Only {@code think} is ever prefilled by a chat template; {@code thought} always opens. */
  private static final String LONE_CLOSING_TAG = "</think>";

  /** Markdown: more indentation makes a line an indented code block. */
  private static final int MAX_INDENT = 3;

  private static final List<String> CODE_FENCES = List.of("```", "~~~");

  private LeadingReasoningText() {}

  /**
   * The answer without its leading reasoning text and the whitespace around it. A leading block
   * that is never closed - the output limit cut the model off while reasoning - leaves no answer at
   * all; reasoning without an opening tag that is cut off stays, as nothing marks it.
   */
  public static Stripped strip(String text) {
    if (text == null) {
      return new Stripped(null, 0, false, false);
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
        return new Stripped("", text.length(), true, false);
      }
      position = end + closingTag.length();
    }
    boolean withoutOpeningTag = false;
    if (position == 0) {
      int loneClosingTag = loneClosingTagAt(text);
      if (loneClosingTag < 0) {
        return new Stripped(text, 0, false, false);
      }
      position = loneClosingTag + LONE_CLOSING_TAG.length();
      withoutOpeningTag = true;
    }
    int answerStart = skipWhitespace(text, position);
    return new Stripped(text.substring(answerStart), answerStart, false, withoutOpeningTag);
  }

  /** Whether {@code text} opens with a reasoning block, closed or not. */
  public static boolean startsWithBlock(String text) {
    return text != null && openingTagAt(text, skipWhitespace(text, 0)) != null;
  }

  /** Where the reasoning ends in an answer whose opening tag was in the prompt, or -1. */
  private static int loneClosingTagAt(String text) {
    int index = text.indexOf(LONE_CLOSING_TAG);
    if (index < 0 || !aloneOnItsLine(text, index)) {
      return -1;
    }
    String before = text.substring(0, index);
    for (String tag : TAGS) {
      if (before.contains("<" + tag + ">")) {
        return -1;
      }
    }
    return insideCodeFence(before) ? -1 : index;
  }

  private static boolean aloneOnItsLine(String text, int index) {
    int lineStart = text.lastIndexOf('\n', index - 1) + 1;
    if (index - lineStart > MAX_INDENT
        || !text.substring(lineStart, index).chars().allMatch(c -> c == ' ')) {
      return false;
    }
    for (int i = index + LONE_CLOSING_TAG.length(); i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        return true;
      }
      if (c != ' ' && c != '\t' && c != '\r') {
        return false;
      }
    }
    return true;
  }

  /** Whether a fence line ({@code ```} or {@code ~~~}) opened in {@code text} is still open. */
  private static boolean insideCodeFence(String text) {
    String openFence = null;
    for (String line : text.split("\n", -1)) {
      String fence = fenceOf(line);
      if (fence == null) {
        continue;
      }
      if (openFence == null) {
        openFence = fence;
      } else if (openFence.equals(fence)) {
        openFence = null;
      }
    }
    return openFence != null;
  }

  private static String fenceOf(String line) {
    int indent = 0;
    while (indent < line.length() && line.charAt(indent) == ' ') {
      indent++;
    }
    if (indent > MAX_INDENT) {
      return null;
    }
    for (String fence : CODE_FENCES) {
      if (line.startsWith(fence, indent)) {
        return fence;
      }
    }
    return null;
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
   * @param withoutOpeningTag whether the dropped reasoning ended in a lone closing tag
   */
  public record Stripped(
      String text, int removedCharacters, boolean unclosed, boolean withoutOpeningTag) {}
}
