package io.opaa.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stufe 1 (#937) of citation plausibility checking: a deterministic, LLM-free comparison of hard
 * facts (money amounts, dates, paragraph references, other "hard" numbers with a thousands
 * separator or decimal comma) between a statement in the answer and the text of the chunk it cites.
 * {@link CitationValidator} calls {@link #isSupportedByChunk} only for citations that already
 * passed the existing retrieval-based check (#386) - this class only tightens that verdict, never
 * loosens it.
 *
 * <p>Deliberately conservative (#937 acceptance criterion: a false positive - flagging a genuinely
 * correct citation - is worse than a false negative): a statement that yields no extractable fact
 * is always treated as supported, and a fact is only ever compared against facts of its own kind
 * extracted the same way from the chunk text - it is never inferred that an absent fact is wrong,
 * only that it could not be confirmed in this specific chunk.
 */
final class CitationFactChecker {

  private CitationFactChecker() {}

  private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b");

  // A plain digit run ("1234") is accepted alongside the properly grouped form ("1.234") - a
  // model or a human routinely omits the thousands separator, and "234" truncated out of "1234"
  // would silently compare a wrong value rather than the intended one.
  private static final String INTEGER_PART = "(?:\\d{1,3}(?:\\.\\d{3})+|\\d+)";

  // Requires exactly two cent digits ("27 Euro 20") - a single digit is ambiguous between tens
  // and units of a cent value, and an ambiguous read must not become a fact comparison.
  private static final Pattern EURO_CENT_SPLIT =
      Pattern.compile("(" + INTEGER_PART + ")\\s*Euro\\s+(\\d{2})\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern MONEY_AMOUNT_FIRST =
      Pattern.compile(
          "(" + INTEGER_PART + "(?:,\\d{1,2})?)\\s*(?:€|EUR\\b|Euro\\b)", Pattern.CASE_INSENSITIVE);

  private static final Pattern MONEY_CURRENCY_FIRST =
      Pattern.compile(
          "(?:€|EUR\\b)\\s*(" + INTEGER_PART + "(?:,\\d{1,2})?)", Pattern.CASE_INSENSITIVE);

  private static final Pattern PARAGRAPH_REF =
      Pattern.compile("(?:§\\s*|Paragraph\\s+)(\\d+[a-zA-Z]?)", Pattern.CASE_INSENSITIVE);

  // Requires a thousands separator or a decimal comma - a bare small integer ("3 Tage", "Schritt
  // 2") is common prose and too weak a signal to compare across statement and chunk.
  private static final Pattern HARD_NUMBER =
      Pattern.compile("\\b\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?\\b|\\b\\d+,\\d+\\b");

  /**
   * {@code true} when every hard fact {@link #extractFacts} finds in {@code statement} also occurs
   * in {@code chunkText} - vacuously {@code true} when {@code statement} carries no extractable
   * fact at all, per this class's conservative contract.
   */
  static boolean isSupportedByChunk(String statement, String chunkText) {
    Set<String> statementFacts = extractFacts(statement);
    if (statementFacts.isEmpty()) {
      return true;
    }
    return extractFacts(chunkText).containsAll(statementFacts);
  }

  /**
   * Extracts canonical fact tokens ({@code "MONEY:<cents>"}, {@code "DATE:<iso>"}, {@code
   * "PARA:<number>"}, {@code "NUM:<value>"}) from {@code text}. Each pattern below blanks out the
   * span it matched before the next pattern runs, so a single occurrence (e.g. "27 Euro 20") is
   * never also picked up by a more generic later pattern (e.g. the bare hard-number pattern).
   */
  static Set<String> extractFacts(String text) {
    if (text == null || text.isBlank()) {
      return Set.of();
    }
    StringBuilder buffer = new StringBuilder(text);
    Set<String> facts = new HashSet<>();

    extractInto(facts, buffer, DATE, CitationFactChecker::dateFact);
    extractInto(facts, buffer, EURO_CENT_SPLIT, CitationFactChecker::euroCentSplitFact);
    extractInto(facts, buffer, MONEY_AMOUNT_FIRST, CitationFactChecker::moneyFact);
    extractInto(facts, buffer, MONEY_CURRENCY_FIRST, CitationFactChecker::moneyFact);
    extractInto(facts, buffer, PARAGRAPH_REF, CitationFactChecker::paragraphFact);
    extractInto(facts, buffer, HARD_NUMBER, CitationFactChecker::hardNumberFact);

    return facts;
  }

  private interface FactBuilder {
    String build(Matcher matcher);
  }

  private static void extractInto(
      Set<String> facts, StringBuilder buffer, Pattern pattern, FactBuilder factBuilder) {
    Matcher matcher = pattern.matcher(buffer);
    while (matcher.find()) {
      String fact = factBuilder.build(matcher);
      if (fact != null) {
        facts.add(fact);
      }
      for (int i = matcher.start(); i < matcher.end(); i++) {
        buffer.setCharAt(i, ' ');
      }
    }
  }

  private static String dateFact(Matcher matcher) {
    try {
      LocalDate date =
          LocalDate.of(
              Integer.parseInt(matcher.group(3)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(1)));
      return "DATE:" + date;
    } catch (DateTimeException e) {
      // Not an actual calendar date (e.g. "3.14.2026") - conservatively not treated as a fact.
      return null;
    }
  }

  private static String euroCentSplitFact(Matcher matcher) {
    BigDecimal euros = parseGermanAmount(matcher.group(1));
    long cents = euros.movePointRight(2).longValueExact() + Long.parseLong(matcher.group(2));
    return "MONEY:" + cents;
  }

  private static String moneyFact(Matcher matcher) {
    BigDecimal amount = parseGermanAmount(matcher.group(1));
    long cents = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    return "MONEY:" + cents;
  }

  private static String paragraphFact(Matcher matcher) {
    return "PARA:" + matcher.group(1).toUpperCase(java.util.Locale.ROOT);
  }

  private static String hardNumberFact(Matcher matcher) {
    return "NUM:" + parseGermanAmount(matcher.group()).stripTrailingZeros().toPlainString();
  }

  /**
   * Parses a German-formatted number ({@code "."} as thousands separator, {@code ","} as decimal
   * separator) into a {@link BigDecimal} - e.g. {@code "1.234,56"} -&gt; {@code 1234.56}, {@code
   * "27"} -&gt; {@code 27}.
   */
  private static BigDecimal parseGermanAmount(String raw) {
    return new BigDecimal(raw.replace(".", "").replace(",", "."));
  }
}
