package io.opaa.format.shared;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans extracted text before it reaches index and prompt. Removed and counted is only what has no
 * place in ordinary text: the tag block, the zero-width space, a byte order mark inside the text
 * and BiDi embeddings and overrides. BiDi isolates without their partner are counted but kept,
 * since a chunk boundary can split a pair. Joiners, non-joiners, variation selectors and direction
 * marks stay: emoji and several scripts need them. Soft hyphens go without a finding, exotic spaces
 * become plain ones, role tokens are defused, injection phrases only counted. No confusables
 * mapping.
 */
public final class IngestTextSanitizer {

  /** What the cleanup found in one text; counted per class, never with content. */
  public enum Finding {
    /** Removed invisible or direction-changing characters, and BiDi isolates without partner. */
    INVISIBLE_CHARACTERS,
    /** Defused role tokens such as {@code <|im_start|>} or {@code [INST]}. */
    ROLE_TOKEN,
    /** Phrases like "ignoriere alle vorherigen Anweisungen" or "ignore previous instructions". */
    INSTRUCTION_PHRASE,
    /** URLs whose query carries a placeholder for data, such as {@code ?d=DATEN}. */
    DATA_URL_PLACEHOLDER
  }

  /** The cleaned text and the findings per class; a class without a hit is absent. */
  public record Result(String text, Map<Finding, Integer> findings) {
    public Result {
      Map<Finding, Integer> copy = new EnumMap<>(Finding.class);
      copy.putAll(findings);
      findings = Collections.unmodifiableMap(copy);
    }

    public boolean hasFindings() {
      return !findings.isEmpty();
    }
  }

  private static final int SOFT_HYPHEN = 0x00AD;

  private static final int BYTE_ORDER_MARK = 0xFEFF;

  private static final int POP_DIRECTIONAL_ISOLATE = 0x2069;

  /** Role tokens between bars, ASCII or full-width: {@code <|im_start|>}, {@code <｜User｜>}. */
  private static final Pattern ROLE_TOKEN =
      Pattern.compile("<[|\\uFF5C]([^|\\uFF5C<>\\s]{1,40})[|\\uFF5C]>");

  /**
   * Turn tokens without bars, exactly as models spell them: {@code <start_of_turn>}, {@code <bos>}.
   * {@code <s>} is left alone, as it is also the HTML strike-through; the answer prompt defuses it
   * on its own.
   */
  private static final Pattern TURN_TOKEN =
      Pattern.compile("<(start_of_turn|end_of_turn|bos|eos)>");

  private static final Pattern INSTRUCTION_MARKER =
      Pattern.compile("\\[\\s*/?\\s*INST\\s*]|<<\\s*/?\\s*SYS\\s*>>", Pattern.CASE_INSENSITIVE);

  private static final Pattern INSTRUCTION_PHRASE =
      Pattern.compile(
          "ignorier(?:e|en sie)\\s+(?:alle\\s+)?(?:vorherigen|bisherigen|obigen)\\s+anweisungen"
              + "|vergiss\\s+(?:alle\\s+)?(?:vorherigen|bisherigen)\\s+anweisungen"
              + "|ignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern DATA_URL_PLACEHOLDER =
      Pattern.compile("https?://\\S+[?&][\\w-]+=(?:DATEN|DATA)\\b");

  private IngestTextSanitizer() {}

  /**
   * {@code text} cleaned, with what was found; {@code null} stays {@code null} without findings.
   */
  public static Result sanitize(String text) {
    if (text == null) {
      return new Result(null, Map.of());
    }
    Map<Finding, Integer> findings = new EnumMap<>(Finding.class);
    int[] codePoints = text.codePoints().toArray();
    boolean[] unpaired = unpairedIsolates(codePoints);
    StringBuilder cleaned = new StringBuilder(text.length());
    int removed = 0;
    for (int i = 0; i < codePoints.length; i++) {
      int cp = codePoints[i];
      if (isExoticSpace(cp)) {
        cleaned.append(' ');
      } else if (cp == SOFT_HYPHEN || (cp == BYTE_ORDER_MARK && i == 0)) {
        // typography of word-processor exports, or the mark of the encoding: no finding
      } else if (unpaired[i]) {
        removed++;
        cleaned.appendCodePoint(cp);
      } else if (isHidden(cp)) {
        removed++;
      } else {
        cleaned.appendCodePoint(cp);
      }
    }
    count(findings, Finding.INVISIBLE_CHARACTERS, removed);
    String result = cleaned.toString();
    Matcher role = ROLE_TOKEN.matcher(result);
    int roleTokens = 0;
    StringBuilder defused = new StringBuilder();
    while (role.find()) {
      roleTokens++;
      role.appendReplacement(defused, Matcher.quoteReplacement("‹" + role.group(1) + "›"));
    }
    role.appendTail(defused);
    Matcher turn = TURN_TOKEN.matcher(defused);
    StringBuilder turnsDefused = new StringBuilder();
    while (turn.find()) {
      roleTokens++;
      turn.appendReplacement(turnsDefused, Matcher.quoteReplacement("‹" + turn.group(1) + "›"));
    }
    turn.appendTail(turnsDefused);
    Matcher marker = INSTRUCTION_MARKER.matcher(turnsDefused);
    StringBuilder markersDefused = new StringBuilder();
    while (marker.find()) {
      roleTokens++;
      marker.appendReplacement(markersDefused, "(Markierung)");
    }
    marker.appendTail(markersDefused);
    result = markersDefused.toString();
    count(findings, Finding.ROLE_TOKEN, roleTokens);
    count(findings, Finding.INSTRUCTION_PHRASE, occurrences(INSTRUCTION_PHRASE, result));
    count(findings, Finding.DATA_URL_PLACEHOLDER, occurrences(DATA_URL_PLACEHOLDER, result));
    return new Result(result, findings);
  }

  /**
   * Characters with no place in ordinary text; isolates are judged by {@link #unpairedIsolates}.
   */
  private static boolean isHidden(int cp) {
    return (cp >= 0xE0000 && cp <= 0xE007F)
        || (cp >= 0x202A && cp <= 0x202E)
        || cp == 0x200B
        || cp == BYTE_ORDER_MARK;
  }

  /**
   * Marks every BiDi isolate initiator (U+2066 to U+2068) without a closing U+2069 and every U+2069
   * without an opener; a matched pair, as word processors write it, stays.
   */
  private static boolean[] unpairedIsolates(int[] codePoints) {
    boolean[] unpaired = new boolean[codePoints.length];
    Deque<Integer> open = new ArrayDeque<>();
    for (int i = 0; i < codePoints.length; i++) {
      int cp = codePoints[i];
      if (cp >= 0x2066 && cp <= 0x2068) {
        open.push(i);
      } else if (cp == POP_DIRECTIONAL_ISOLATE) {
        if (open.isEmpty()) {
          unpaired[i] = true;
        } else {
          open.pop();
        }
      }
    }
    open.forEach(index -> unpaired[index] = true);
    return unpaired;
  }

  private static boolean isExoticSpace(int cp) {
    return cp == 0x00A0
        || (cp >= 0x2000 && cp <= 0x200A)
        || cp == 0x202F
        || cp == 0x205F
        || cp == 0x3000;
  }

  private static int occurrences(Pattern pattern, CharSequence text) {
    Matcher matcher = pattern.matcher(text);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static void count(Map<Finding, Integer> findings, Finding finding, int count) {
    if (count > 0) {
      findings.merge(finding, count, Integer::sum);
    }
  }
}
