package io.opaa.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stufe 1 (#937) of citation plausibility checking: a deterministic, LLM-free comparison of hard
 * facts (money amounts, dates, paragraph references, other "hard" numbers with a thousands
 * separator or decimal comma) between a statement in the answer and the text of the chunk(s) it
 * cites. {@link CitationValidator} calls this class only for citations that already passed the
 * existing retrieval-based check (#386) - this class only tightens that verdict, never loosens it.
 *
 * <p>Deliberately conservative (#937 acceptance criterion, sharpened by #939 review: a false
 * positive - flagging a genuinely correct citation - is worse than a false negative):
 *
 * <ul>
 *   <li>a statement that yields no extractable fact is always treated as supported;
 *   <li>a fact is only ever compared against facts of the same <b>category</b> extracted the same
 *       way from the chunk text ({@link Fact#category()}: money amounts and other "hard" numbers
 *       share the {@code "AMOUNT"} category, since a money amount is only ever a formatted number -
 *       "37,00" in a fee table column headed "EUR" is the same fact as "37,00 €" in prose - while a
 *       date or a paragraph reference stays its own category);
 *   <li>when the chunk contains <b>no</b> fact of a statement fact's category at all, that
 *       statement fact is never compared and never flags the citation - an absent category is
 *       "could not be confirmed here", not "contradicted"; only a same-category fact with a
 *       genuinely different value is a contradiction.
 * </ul>
 */
final class CitationFactChecker {

  private CitationFactChecker() {}

  private static final String AMOUNT_CATEGORY = "AMOUNT";
  private static final String DATE_CATEGORY = "DATE";
  private static final String PARAGRAPH_CATEGORY = "PARA";

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

  // "Paragraf" (without "h") is a common, equally correct German spelling alongside "Paragraph".
  private static final Pattern PARAGRAPH_REF =
      Pattern.compile(
          "(?:§\\s*|Paragraph\\s+|Paragraf\\s+)(\\d+[a-zA-Z]?)", Pattern.CASE_INSENSITIVE);

  // Requires a thousands separator or a decimal comma - a bare small integer ("3 Tage", "Schritt
  // 2") is common prose and too weak a signal to compare across statement and chunk.
  private static final Pattern HARD_NUMBER =
      Pattern.compile("\\b\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?\\b|\\b\\d+,\\d+\\b");

  /**
   * One extracted fact: a comparison {@link #category()} plus every canonical form it could be
   * written as - e.g. a money amount also carries its bare-number form ({@code "NUM:37"} next to
   * {@code "MONEY:3700"}), so it is recognised even where the chunk names the same value without a
   * currency marker (a fee table column headed "EUR").
   */
  record Fact(String category, Set<String> forms) {}

  private record PositionedFact(int start, Fact fact) {}

  /**
   * {@code true} when every hard fact {@link #extractFacts} finds in {@code statement} is supported
   * by {@code chunkText} - see this class's Javadoc for what "supported" means (same-category
   * match, or the category simply absent from the chunk). Vacuously {@code true} when {@code
   * statement} carries no extractable fact, or when {@code chunkText} is {@code null}.
   */
  static boolean isSupportedByChunk(String statement, String chunkText) {
    Set<Fact> statementFacts = extractFacts(statement);
    if (statementFacts.isEmpty()) {
      return true;
    }
    return allSupported(statementFacts, chunkText);
  }

  /**
   * Like {@link #isSupportedByChunk}, but only checks the single fact {@link #nearestFact} finds
   * closest to the end of {@code statement} - the fact {@link CitationValidator} takes a citation
   * marker to actually belong to (#939 review, finding 3(a)), rather than every fact anywhere in
   * the statement.
   */
  static boolean isNearestFactSupportedByChunk(String statement, String chunkText) {
    Optional<Fact> nearest = nearestFact(statement);
    return nearest.map(fact -> allSupported(Set.of(fact), chunkText)).orElse(true);
  }

  private static boolean allSupported(Set<Fact> statementFacts, String chunkText) {
    if (chunkText == null) {
      return true;
    }
    Map<String, Set<String>> chunkFormsByCategory = formsByCategory(extractFacts(chunkText));
    for (Fact fact : statementFacts) {
      Set<String> sameCategoryForms = chunkFormsByCategory.get(fact.category());
      if (sameCategoryForms == null) {
        // The chunk carries no fact of this category at all - "not confirmable here", not a
        // contradiction (this class's conservative contract).
        continue;
      }
      if (fact.forms().stream().noneMatch(sameCategoryForms::contains)) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, Set<String>> formsByCategory(Set<Fact> facts) {
    Map<String, Set<String>> result = new HashMap<>();
    for (Fact fact : facts) {
      result.computeIfAbsent(fact.category(), key -> new HashSet<>()).addAll(fact.forms());
    }
    return result;
  }

  /** Every hard fact extractable from {@code text}, in no particular order. */
  static Set<Fact> extractFacts(String text) {
    Set<Fact> facts = new LinkedHashSet<>();
    for (PositionedFact positioned : extractPositionedFacts(text)) {
      facts.add(positioned.fact());
    }
    return facts;
  }

  /**
   * The single fact whose match starts closest to the end of {@code text} - used by {@link
   * #isNearestFactSupportedByChunk} to resolve "the fact this citation marker belongs to" when a
   * statement carries more than one (#939 review, finding 3(a): a sentence enumerating several
   * documents' fees, each with its own marker, must not compare an earlier fee against the wrong
   * marker's chunk).
   */
  static Optional<Fact> nearestFact(String text) {
    return extractPositionedFacts(text).stream()
        .max(Comparator.comparingInt(PositionedFact::start))
        .map(PositionedFact::fact);
  }

  /**
   * Extracts every fact together with the offset (into {@code text}) its match started at. Each
   * pattern below blanks out the span it matched before the next pattern runs, so a single
   * occurrence (e.g. "27 Euro 20") is never also picked up by a more generic later pattern (e.g.
   * the bare hard-number pattern).
   */
  private static List<PositionedFact> extractPositionedFacts(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    StringBuilder buffer = new StringBuilder(text);
    List<PositionedFact> facts = new ArrayList<>();

    extractInto(facts, buffer, DATE, CitationFactChecker::dateFact);
    extractInto(facts, buffer, EURO_CENT_SPLIT, CitationFactChecker::euroCentSplitFact);
    extractInto(facts, buffer, MONEY_AMOUNT_FIRST, CitationFactChecker::moneyFact);
    extractInto(facts, buffer, MONEY_CURRENCY_FIRST, CitationFactChecker::moneyFact);
    extractInto(facts, buffer, PARAGRAPH_REF, CitationFactChecker::paragraphFact);
    extractInto(facts, buffer, HARD_NUMBER, CitationFactChecker::hardNumberFact);

    return facts;
  }

  private interface FactBuilder {
    Fact build(Matcher matcher);
  }

  private static void extractInto(
      List<PositionedFact> facts, StringBuilder buffer, Pattern pattern, FactBuilder factBuilder) {
    Matcher matcher = pattern.matcher(buffer);
    while (matcher.find()) {
      Fact fact = factBuilder.build(matcher);
      if (fact != null) {
        facts.add(new PositionedFact(matcher.start(), fact));
      }
      for (int i = matcher.start(); i < matcher.end(); i++) {
        buffer.setCharAt(i, ' ');
      }
    }
  }

  private static Fact dateFact(Matcher matcher) {
    try {
      LocalDate date =
          LocalDate.of(
              Integer.parseInt(matcher.group(3)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(1)));
      return new Fact(DATE_CATEGORY, Set.of("DATE:" + date));
    } catch (DateTimeException e) {
      // Not an actual calendar date (e.g. "3.14.2026") - conservatively not treated as a fact.
      return null;
    }
  }

  private static Fact euroCentSplitFact(Matcher matcher) {
    BigDecimal euros = parseGermanAmount(matcher.group(1));
    long cents = euros.movePointRight(2).longValueExact() + Long.parseLong(matcher.group(2));
    return moneyFactOf(cents);
  }

  private static Fact moneyFact(Matcher matcher) {
    BigDecimal amount = parseGermanAmount(matcher.group(1));
    long cents = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    return moneyFactOf(cents);
  }

  /**
   * A money amount also carries its plain-number form ({@code "NUM:<value>"}) so it is recognised
   * against a chunk that states the identical value without a currency marker - e.g. a fee table
   * column headed "EUR" once, with bare numbers underneath (#939 review, finding 2).
   */
  private static Fact moneyFactOf(long cents) {
    BigDecimal euros = BigDecimal.valueOf(cents).movePointLeft(2);
    return new Fact(
        AMOUNT_CATEGORY,
        Set.of("MONEY:" + cents, "NUM:" + euros.stripTrailingZeros().toPlainString()));
  }

  private static Fact paragraphFact(Matcher matcher) {
    return new Fact(
        PARAGRAPH_CATEGORY, Set.of("PARA:" + matcher.group(1).toUpperCase(Locale.ROOT)));
  }

  private static Fact hardNumberFact(Matcher matcher) {
    String value = parseGermanAmount(matcher.group()).stripTrailingZeros().toPlainString();
    return new Fact(AMOUNT_CATEGORY, Set.of("NUM:" + value));
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
