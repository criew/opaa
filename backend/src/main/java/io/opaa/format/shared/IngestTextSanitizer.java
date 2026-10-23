package io.opaa.format.shared;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans extracted text before it reaches index and prompt: removes characters that carry no
 * meaning in an administrative document but hide text from a reader (Unicode tag block, BiDi
 * controls, zero-width characters outside emoji sequences, variation selectors outside emoji, soft
 * hyphens), replaces exotic spaces by a plain one and defuses chat-template role tokens. Typical
 * injection phrases are only counted, never removed. No confusables mapping: it would falsify
 * legitimate foreign-language text.
 */
public final class IngestTextSanitizer {

  /** What the cleanup found in one text; counted per class, never with content. */
  public enum Finding {
    /** Removed invisible or direction-changing characters. */
    INVISIBLE_CHARACTERS,
    /** Defused role tokens such as {@code <|im_start|>} or {@code [INST]}. */
    ROLE_TOKEN,
    /** Phrases like "ignoriere alle vorherigen Anweisungen" or "you are now". */
    INSTRUCTION_PHRASE,
    /** URLs whose query carries a placeholder for data, such as {@code ?d=DATEN}. */
    DATA_URL_PLACEHOLDER
  }

  /** The cleaned text and the findings per class; a class without a hit is absent. */
  public record Result(String text, Map<Finding, Integer> findings) {
    public Result {
      findings = Collections.unmodifiableMap(new EnumMap<>(findings));
    }

    public boolean hasFindings() {
      return !findings.isEmpty();
    }
  }

  private static final Pattern ROLE_TOKEN = Pattern.compile("<\\|([^|<>\\s]{1,40})\\|>");

  private static final Pattern INSTRUCTION_MARKER =
      Pattern.compile("\\[\\s*/?\\s*INST\\s*]|<<\\s*/?\\s*SYS\\s*>>", Pattern.CASE_INSENSITIVE);

  private static final Pattern INSTRUCTION_PHRASE =
      Pattern.compile(
          "ignorier(?:e|en sie)\\s+(?:alle\\s+)?(?:vorherigen|bisherigen|obigen)\\s+anweisungen"
              + "|vergiss\\s+(?:alle\\s+)?(?:vorherigen|bisherigen)\\s+anweisungen"
              + "|du\\s+bist\\s+(?:jetzt|ab\\s+sofort|nun)\\b"
              + "|ignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions"
              + "|you\\s+are\\s+now\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern DATA_URL_PLACEHOLDER =
      Pattern.compile(
          "https?://\\S+[?&][\\w-]+=(?:DATEN|DATA|\\{[^}\\s]{1,40}}|<[^>\\s]{1,40}>)",
          Pattern.CASE_INSENSITIVE);

  private IngestTextSanitizer() {}

  /**
   * {@code text} cleaned, with what was found; {@code null} stays {@code null} without findings.
   */
  public static Result sanitize(String text) {
    if (text == null) {
      return new Result(null, Map.of());
    }
    Map<Finding, Integer> findings = new EnumMap<>(Finding.class);
    StringBuilder cleaned = new StringBuilder(text.length());
    int removed = 0;
    int[] codePoints = text.codePoints().toArray();
    for (int i = 0; i < codePoints.length; i++) {
      int cp = codePoints[i];
      int previous = i > 0 ? codePoints[i - 1] : -1;
      int next = i + 1 < codePoints.length ? codePoints[i + 1] : -1;
      if (isExoticSpace(cp)) {
        cleaned.append(' ');
      } else if (cp == 0x200D && isEmoji(previous) && isEmoji(next)) {
        cleaned.appendCodePoint(cp);
      } else if (isVariationSelector(cp) && isEmoji(previous)) {
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
    Matcher marker = INSTRUCTION_MARKER.matcher(defused);
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

  private static boolean isHidden(int cp) {
    return (cp >= 0xE0000 && cp <= 0xE007F)
        || (cp >= 0x202A && cp <= 0x202E)
        || (cp >= 0x2066 && cp <= 0x2069)
        || cp == 0x200E
        || cp == 0x200F
        || cp == 0x061C
        || (cp >= 0x200B && cp <= 0x200D)
        || cp == 0x2060
        || cp == 0xFEFF
        || cp == 0x00AD
        || isVariationSelector(cp);
  }

  private static boolean isVariationSelector(int cp) {
    return (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
  }

  private static boolean isExoticSpace(int cp) {
    return cp == 0x00A0
        || (cp >= 0x2000 && cp <= 0x200A)
        || cp == 0x202F
        || cp == 0x205F
        || cp == 0x3000;
  }

  /** Pictographic characters an emoji sequence joins or qualifies; a coarse, safe superset. */
  private static boolean isEmoji(int cp) {
    return cp >= 0x1F000 && cp <= 0x1FAFF
        || cp >= 0x2600 && cp <= 0x27BF
        || cp >= 0x2190 && cp <= 0x21FF
        || cp >= 0x2B00 && cp <= 0x2BFF
        || cp == 0x00A9
        || cp == 0x00AE
        || cp == 0x2122;
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
