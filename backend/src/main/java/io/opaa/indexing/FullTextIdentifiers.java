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
 *
 * <p><b>Symmetry is the property everything else hangs on.</b> The same identifier must produce the
 * same lexeme whether it appears in a chunk or in a question - and the two are worded differently:
 * a document writes "Dienstanweisung mit dem Aktenzeichen BAU-DA-2/2024", a person asks "Was regelt
 * die Dienstanweisung BAU-DA-2/2024?". A pattern that needs the keyword therefore only ever fires
 * on one of the two sides, and the protection silently does nothing. Every keyword-led pattern here
 * consequently has a keyword-free structural counterpart, and {@code FullTextIdentifiersTest} pins
 * both wordings of one identifier against each other.
 *
 * <p><b>A candidate is only accepted as an identifier if it is structurally one</b> ({@link
 * #looksLikeIdentifier}: at least one digit and at least one separator). Without that requirement
 * "Aktenzeichen der Satzung" yields the lexeme {@code xakzder}, which then sits at weight {@code A}
 * on every ordinary prose chunk that happens to contain the same phrase - noise at the top of the
 * ranking, produced by the very mechanism meant to sharpen it.
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
  private static final String EMAIL_PREFIX = "xmail";

  /**
   * A law abbreviation as it is actually written in German administrative texts: initial capital
   * plus at least one further capital ({@code BauGB}, {@code VwVfG}, {@code VGS}, {@code BGB}). The
   * second capital is what keeps an ordinary capitalized word ({@code Satzung}) from being read as
   * a law abbreviation.
   */
  private static final String LAW_ABBREVIATION = "[A-ZÄÖÜ][A-Za-zÄÖÜäöüß]*[A-ZÄÖÜ][A-Za-zÄÖÜäöüß]*";

  /**
   * {@code § 34}, {@code §§ 34}, {@code § 3 Abs. 2 VGS}, {@code § 35 BauGB} - and enumerations
   * behind {@code §§}, which administrative texts write as {@code §§ 34, 35 BauGB} or {@code §§ 34
   * und 35 BauGB}. The first group captures the whole number run; {@link #collectParagraphs} splits
   * it, so every number of the enumeration gets its own lexeme instead of only the first.
   */
  private static final Pattern PARAGRAPH =
      Pattern.compile(
          "§{1,2}\\s*(\\d{1,4}[a-z]?(?:\\s*(?:,|und|u\\.)\\s*\\d{1,4}[a-z]?)*)"
              + "(?:\\s*Abs(?:atz|\\.)?\\s*(\\d{1,3}[a-z]?))?"
              + "(?:\\s+("
              + LAW_ABBREVIATION
              + "))?");

  /** Splits the number run {@link #PARAGRAPH} captured into its individual paragraph numbers. */
  private static final Pattern PARAGRAPH_RUN_SEPARATOR = Pattern.compile("\\s*(?:,|und|u\\.)\\s*");

  /** Court-style file numbers: {@code 4 K 1023/24.NW}, {@code 12 A 45/2023}. */
  private static final Pattern FILE_NUMBER =
      Pattern.compile("\\b(\\d{1,4})\\s+([A-Z]{1,3})\\s+(\\d{1,6}/\\d{2,4})(\\.[A-Z]{1,4})?\\b");

  /**
   * Keyword-led file numbers: {@code Az. 12/2024}, {@code Aktenzeichen: 45-2/2023}. {@code \b}
   * after the keyword so {@code Azubi} is a word and not an {@code Az} with a number behind it;
   * {@link #looksLikeIdentifier} then rejects whatever the keyword is followed by in ordinary prose
   * ({@code Aktenzeichen der Satzung}).
   */
  private static final Pattern KEYWORD_FILE_NUMBER =
      Pattern.compile(
          "\\b(?:Az|AZ|Aktenzeichen)\\b\\.?\\s*:?\\s*([A-Za-z0-9][A-Za-z0-9./\\-]{1,30})");

  /**
   * The keyword-free counterpart of {@link #KEYWORD_FILE_NUMBER}: the shape administrative file,
   * Dienstanweisungs- and Formularnummern actually have - an uppercase department or form
   * abbreviation followed by hyphen-separated parts, optionally with a year ({@code BAU-DA-2/2024},
   * {@code SOZ-DA-1/2023}, {@code KAE-07}, {@code BUE-08}).
   *
   * <p>This is the pattern that makes the protection work at all on the question side: a question
   * names the number bare ("Was regelt die Dienstanweisung BAU-DA-2/2024?"), the document names it
   * behind a keyword. {@link #looksLikeIdentifier} keeps it from firing on ordinary hyphenated
   * uppercase abbreviations, which carry no digit.
   */
  private static final Pattern STRUCTURED_FILE_NUMBER =
      Pattern.compile("\\b([A-Z]{2,4}(?:-[A-Z0-9]{1,4})+(?:/\\d{2,4})?)\\b");

  /**
   * Erlass- and Drucksachen numbers: {@code Drucksache 19/1234}, {@code Drs. 19/1234}, {@code
   * Erlass Nr. 12/2024}, {@code Nr. 45-2/2023}. The number part must itself carry a separator, so a
   * plain {@code Nr. 5} - which is a list item, not an identifier - produces nothing.
   */
  private static final Pattern ORDINANCE_NUMBER =
      Pattern.compile(
          "\\b(?:Drucksache|Drs|Erlass(?:\\s+Nr)?|Runderlass(?:\\s+Nr)?|Nr|Nummer)\\.?\\s*:?\\s*"
              + "(\\d{1,6}\\s*[-/]\\s*\\d{1,6}(?:\\s*[-/]\\s*\\d{1,6})?)");

  /**
   * An email address (#1130 Befund 1, Querschnittsregel a). PostgreSQL's own parser already keeps
   * an email address as one {@code email}-class token in {@code to_tsvector} - the gap this pattern
   * closes is on the <em>question</em> side: {@code io.opaa.query.FullTextChunkSearch#wordTokens}
   * splits a question at every non-alphanumeric character, so "max.mustermann@example.org" asked
   * back becomes four separate word tokens that never match the one token the chunk carries
   * (confirmed against a live PostgreSQL: {@code to_tsvector('german', '...
   * max.mustermann@example.org ...') @@ to_tsquery('german', 'max|mustermann|example|org')} is
   * {@code false} when none of the four also occurs as an ordinary word). Carrying the address as
   * an undecomposed lexeme on both sides restores the match, the same fix this class already
   * applies to §-references and file numbers.
   */
  private static final Pattern EMAIL_ADDRESS =
      Pattern.compile("\\b([A-Za-z0-9][A-Za-z0-9._%+-]*@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

  /** At least one digit and at least one separator - see {@link #looksLikeIdentifier}. */
  private static final Pattern IDENTIFIER_SHAPE =
      Pattern.compile("(?=[A-Za-z0-9./\\-]*\\d)[A-Za-z0-9]+(?:[./\\-][A-Za-z0-9]+)+");

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
    collect(FILE_NUMBER, text, FILE_NUMBER_PREFIX, false, lexemes);
    collect(KEYWORD_FILE_NUMBER, text, FILE_NUMBER_PREFIX, true, lexemes);
    collect(STRUCTURED_FILE_NUMBER, text, FILE_NUMBER_PREFIX, true, lexemes);
    collect(ORDINANCE_NUMBER, text, ORDINANCE_NUMBER_PREFIX, false, lexemes);
    collect(EMAIL_ADDRESS, text, EMAIL_PREFIX, false, lexemes);
    List<String> result = new ArrayList<>(lexemes);
    return result.size() <= MAX_LEXEMES
        ? List.copyOf(result)
        : List.copyOf(result.subList(0, MAX_LEXEMES));
  }

  /**
   * One lexeme per paragraph number of the match, plus the Absatz- and law-qualified forms. In an
   * enumeration ({@code §§ 34, 35 BauGB}) the law qualifies every number - that is what the
   * notation means - while an Absatz does not: which of the listed paragraphs it belongs to is not
   * decidable from the text, so it is only applied to a single-number reference.
   */
  private static void collectParagraphs(String text, Set<String> lexemes) {
    Matcher matcher = PARAGRAPH.matcher(text);
    while (matcher.find()) {
      String[] numbers = PARAGRAPH_RUN_SEPARATOR.split(matcher.group(1).trim());
      String absatz = numbers.length == 1 ? normalize(matcher.group(2)) : "";
      String law = normalize(matcher.group(3));
      for (String rawNumber : numbers) {
        String number = normalize(rawNumber);
        if (number.isEmpty()) {
          continue;
        }
        lexemes.add(PARAGRAPH_PREFIX + number);
        if (!absatz.isEmpty()) {
          lexemes.add(PARAGRAPH_PREFIX + number + "abs" + absatz);
        }
        if (!law.isEmpty()) {
          lexemes.add(PARAGRAPH_PREFIX + number + law);
          if (!absatz.isEmpty()) {
            lexemes.add(PARAGRAPH_PREFIX + number + "abs" + absatz + law);
          }
        }
      }
    }
  }

  /**
   * @param requireIdentifierShape whether the matched text must pass {@link #looksLikeIdentifier}.
   *     Set for the patterns whose match is only loosely constrained - a keyword followed by
   *     whatever comes next, an uppercase abbreviation with hyphens - and unset for those whose
   *     shape is already spelled out in the pattern itself.
   */
  private static void collect(
      Pattern pattern,
      String text,
      String prefix,
      boolean requireIdentifierShape,
      Set<String> lexemes) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      if (requireIdentifierShape && !looksLikeIdentifier(matcher.group(1))) {
        continue;
      }
      StringBuilder joined = new StringBuilder();
      for (int group = 1; group <= matcher.groupCount(); group++) {
        joined.append(normalize(matcher.group(group)));
      }
      if (!joined.isEmpty()) {
        lexemes.add(prefix + joined);
      }
    }
  }

  /**
   * Whether {@code candidate} has the shape of an identifier rather than of a word: at least one
   * digit and at least one separator. The guard against a loosely constrained pattern turning
   * ordinary prose behind a keyword ("Aktenzeichen der Satzung") into a weight-{@code A} lexeme.
   */
  static boolean looksLikeIdentifier(String candidate) {
    return candidate != null && IDENTIFIER_SHAPE.matcher(candidate).matches();
  }

  /** Lowercase ASCII alphanumerics only - see this class's own Javadoc for why that matters. */
  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return NON_ALPHANUMERIC.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
  }
}
