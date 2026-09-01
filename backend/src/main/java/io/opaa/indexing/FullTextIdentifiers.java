package io.opaa.indexing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The curated pattern list behind the lexical path's identifier protection
 * (docs/features/hybrid-retrieval.md, "Die deutschen Besonderheiten"): paragraph references, file
 * numbers and Erlass-/Drucksachen numbers are identifiers, not words, and are carried as
 * <b>undecomposed lexemes</b> next to the German analysis chain so that stemming and tokenization
 * cannot destroy them. "§ 34" and "§ 35" stay distinguishable; without this, both reduce to the
 * bare number and the whole path is worthless for its main purpose.
 *
 * <p><b>Deliberately a closed list, not a general identifier guess.</b> A wrongly recognized
 * identifier produces a lexeme nobody ever searches for; an unrecognized one is the failure that
 * hurts. Both the write path ({@link FullTextChunkStore#indexChunks}) and the query path ({@code
 * io.opaa.query.FullTextChunkSearch}) call this same method, so a pattern added here takes effect
 * on both sides at once - a lexeme produced from a chunk and a lexeme produced from a question are
 * built by the identical code, or they would never match.
 *
 * <p><b>Every lexeme is lowercase ASCII alphanumeric with a {@code x…} type prefix</b> ({@code
 * xpar}, {@code xakz}, {@code xnr}). Two properties follow from that, and both are load-bearing:
 * such a string can never collide with a German lexeme the stemmer produces, and it passes unquoted
 * through {@code to_tsquery} without carrying any operator character into the query.
 *
 * <p>A paragraph reference always yields its bare form ({@code xpar34}) in addition to any more
 * specific one ({@code xpar34baugb}, {@code xpar3abs2}). The specific lexeme is what separates two
 * documents that both mention § 3; the bare one is what still matches when only one side names the
 * law or the Absatz.
 */
public final class FullTextIdentifiers {

  /**
   * Upper bound on lexemes per text - a defence against a pathological input (a table of hundreds
   * of file numbers) inflating one chunk's {@code tsvector}, not an expected case; real chunks stay
   * far below it.
   */
  static final int MAX_LEXEMES = 64;

  private static final String PARAGRAPH_PREFIX = "xpar";
  private static final String FILE_NUMBER_PREFIX = "xakz";
  private static final String ORDINANCE_NUMBER_PREFIX = "xnr";

  /**
   * A law abbreviation as it is actually written in German administrative texts: initial capital
   * plus at least one further capital ({@code BauGB}, {@code VwVfG}, {@code VGS}, {@code BGB}). The
   * second capital is what keeps an ordinary capitalized word ({@code Satzung}) from being read as
   * a law abbreviation.
   */
  private static final String LAW_ABBREVIATION = "[A-ZÄÖÜ][A-Za-zÄÖÜäöüß]*[A-ZÄÖÜ][A-Za-zÄÖÜäöüß]*";

  /** {@code § 34}, {@code §§ 34}, {@code § 3 Abs. 2 VGS}, {@code § 35 BauGB}. */
  private static final Pattern PARAGRAPH =
      Pattern.compile(
          "§{1,2}\\s*(\\d{1,4}[a-z]?)"
              + "(?:\\s*Abs(?:atz|\\.)?\\s*(\\d{1,3}[a-z]?))?"
              + "(?:\\s+("
              + LAW_ABBREVIATION
              + "))?");

  /** Court-style file numbers: {@code 4 K 1023/24.NW}, {@code 12 A 45/2023}. */
  private static final Pattern FILE_NUMBER =
      Pattern.compile("\\b(\\d{1,4})\\s+([A-Z]{1,3})\\s+(\\d{1,6}/\\d{2,4})(\\.[A-Z]{1,4})?\\b");

  /** Keyword-led file numbers: {@code Az. 12/2024}, {@code Aktenzeichen: 45-2/2023}. */
  private static final Pattern KEYWORD_FILE_NUMBER =
      Pattern.compile("\\b(?:Az|AZ|Aktenzeichen)\\.?\\s*:?\\s*([A-Za-z0-9][A-Za-z0-9./\\-]{2,30})");

  /**
   * Erlass- and Drucksachen numbers: {@code Drucksache 19/1234}, {@code Drs. 19/1234}, {@code
   * Erlass Nr. 12/2024}, {@code Nr. 45-2/2023}. The number part must itself carry a separator, so a
   * plain {@code Nr. 5} - which is a list item, not an identifier - produces nothing.
   */
  private static final Pattern ORDINANCE_NUMBER =
      Pattern.compile(
          "\\b(?:Drucksache|Drs|Erlass(?:\\s+Nr)?|Runderlass(?:\\s+Nr)?|Nr|Nummer)\\.?\\s*:?\\s*"
              + "(\\d{1,6}\\s*[-/]\\s*\\d{1,6}(?:\\s*[-/]\\s*\\d{1,6})?)");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

  private FullTextIdentifiers() {}

  /**
   * Every identifier lexeme {@code text} contains, in order of first appearance and without
   * duplicates. Never {@code null}; empty for text that carries no identifier at all, which is the
   * common case for ordinary prose.
   */
  public static List<String> extract(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    Set<String> lexemes = new LinkedHashSet<>();
    collectParagraphs(text, lexemes);
    collect(FILE_NUMBER, text, FILE_NUMBER_PREFIX, lexemes);
    collect(KEYWORD_FILE_NUMBER, text, FILE_NUMBER_PREFIX, lexemes);
    collect(ORDINANCE_NUMBER, text, ORDINANCE_NUMBER_PREFIX, lexemes);
    List<String> result = new ArrayList<>(lexemes);
    return result.size() <= MAX_LEXEMES
        ? List.copyOf(result)
        : List.copyOf(result.subList(0, MAX_LEXEMES));
  }

  private static void collectParagraphs(String text, Set<String> lexemes) {
    Matcher matcher = PARAGRAPH.matcher(text);
    while (matcher.find()) {
      String number = normalize(matcher.group(1));
      if (number.isEmpty()) {
        continue;
      }
      lexemes.add(PARAGRAPH_PREFIX + number);
      String absatz = normalize(matcher.group(2));
      if (!absatz.isEmpty()) {
        lexemes.add(PARAGRAPH_PREFIX + number + "abs" + absatz);
      }
      String law = normalize(matcher.group(3));
      if (!law.isEmpty()) {
        lexemes.add(PARAGRAPH_PREFIX + number + law);
        if (!absatz.isEmpty()) {
          lexemes.add(PARAGRAPH_PREFIX + number + "abs" + absatz + law);
        }
      }
    }
  }

  private static void collect(Pattern pattern, String text, String prefix, Set<String> lexemes) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      StringBuilder joined = new StringBuilder();
      for (int group = 1; group <= matcher.groupCount(); group++) {
        joined.append(normalize(matcher.group(group)));
      }
      if (joined.length() > 0) {
        lexemes.add(prefix + joined);
      }
    }
  }

  /** Lowercase ASCII alphanumerics only - see this class's own Javadoc for why that matters. */
  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return NON_ALPHANUMERIC.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
  }
}
