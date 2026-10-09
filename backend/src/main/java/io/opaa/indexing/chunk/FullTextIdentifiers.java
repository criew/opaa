package io.opaa.indexing.chunk;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The curated pattern list behind the lexical path's identifier protection
 * (docs/features/hybrid-retrieval.md, "Die deutschen Besonderheiten"): paragraph references, file
 * numbers, Erlass-/Drucksachen numbers, email addresses and technical field names become
 * <b>undecomposed lexemes</b>, so "§ 34" and "§ 35" stay distinguishable under stemming. Write and
 * query path call this same method, or the two sides would build different lexemes and never match.
 *
 * <p>Every lexeme is lowercase ASCII alphanumeric with an {@code x…} type prefix, so it can neither
 * collide with a stemmer lexeme nor carry an operator into {@code to_tsquery}. Every keyword-led
 * pattern has a keyword-free counterpart, and a candidate must satisfy {@link
 * #looksLikeIdentifier}.
 */
public final class FullTextIdentifiers {

  /**
   * Upper bound on lexemes per text, against one chunk's {@code tsvector} growing without limit.
   * Paragraph, file-number, ordinance and email lexemes rarely come near it; field names can: a
   * field table of more than this many names reaches it, and uppercase headings or acronyms earlier
   * in the text take places first. What is cut are field names, in order of appearance.
   */
  static final int MAX_LEXEMES = 64;

  private static final String PARAGRAPH_PREFIX = "xpar";
  private static final String FILE_NUMBER_PREFIX = "xakz";
  private static final String ORDINANCE_NUMBER_PREFIX = "xnr";
  private static final String EMAIL_PREFIX = "xmail";
  private static final String FIELD_NAME_PREFIX = "xfld";

  /**
   * Field names longer than this are not taken: a real field or element name stays far below it,
   * while a base64 blob or a hash in running text also has a lowercase-uppercase transition and
   * would otherwise become one oversized lexeme per occurrence.
   */
  static final int MAX_FIELD_NAME_LENGTH = 64;

  /**
   * Upper bound on the further numbers of a paragraph enumeration ({@code §§ 34, 35}), on the
   * reference parts behind a paragraph ({@code Abs. 1 Nr. 4}) and on the hyphenated parts of a
   * structured file number. Java matches a repeated regex group recursively, so every repetition in
   * these patterns is bounded: no input text can overflow the stack. A chain beyond the bound ends
   * its match there; real citations stay far below it.
   */
  private static final int MAX_REPETITIONS = 16;

  private static final String PARAGRAPH_RUN =
      "\\d{1,4}[a-z]?(?:\\s*(?:,|und|u\\.)\\s*\\d{1,4}[a-z]?){0," + MAX_REPETITIONS + "}";

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
          "§{1,2}\\s*("
              + PARAGRAPH_RUN
              + ")"
              + "(?:\\s*Abs(?:atz|\\.)?\\s*(\\d{1,3}[a-z]?))?"
              + "(?:\\s+("
              + LAW_ABBREVIATION
              + "))?");

  /**
   * A paragraph reference followed by any chain of Absatz, Satz, Nummer, Halbsatz or Buchstabe
   * parts and then a law abbreviation: {@code § 35 Abs. 1 Nr. 4 BauGB}, {@code § 35 S. 1 BauGB}.
   * Only locates the abbreviation as part of the reference, so it yields no field-name lexeme of
   * its own; the paragraph lexemes stay those of {@link #PARAGRAPH}.
   */
  private static final Pattern LAW_OF_PARAGRAPH_REFERENCE =
      Pattern.compile(
          "§{1,2}\\s*"
              + PARAGRAPH_RUN
              + "(?:\\s*(?:Absatz|Abs\\.?|Satz|S\\.|Nummer|Nr\\.?|Halbsatz|Hs\\.?|Buchstabe"
              + "|Buchst\\.?)\\s*(?:\\d{1,4}[a-z]?|[a-z]\\)?)){0,"
              + MAX_REPETITIONS
              + "}"
              + "\\s+("
              + LAW_ABBREVIATION
              + ")");

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
   * Dienstanweisungs- and Formularnummern actually have - an uppercase abbreviation followed by
   * hyphen-separated parts, optionally with a year. This is what makes the protection work on the
   * question side, which names the number bare while the document names it behind a keyword. {@link
   * #looksLikeIdentifier} keeps it off ordinary hyphenated abbreviations, which carry no digit.
   */
  private static final Pattern STRUCTURED_FILE_NUMBER =
      Pattern.compile(
          "\\b([A-Z]{2,4}(?:-[A-Z0-9]{1,4}){1," + MAX_REPETITIONS + "}(?:/\\d{2,4})?)\\b");

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
   * An email address (ingestion-pipelines.md, Querschnittsregel (a)). PostgreSQL keeps one as a
   * single {@code email} token already; the gap is on the question side, where {@code
   * FullTextChunkSearch#wordTokens} splits it at every non-alphanumeric character. Local part and
   * domain are ASCII-only, so an umlaut ends the match early - a missed address, never a false
   * positive, and symmetric because both sides apply this same pattern.
   */
  private static final Pattern EMAIL_ADDRESS =
      Pattern.compile("\\b([A-Za-z0-9][A-Za-z0-9._%+-]*@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");

  /**
   * A maximal run of letters, digits and underscores - the unit a field name is recognized in. A
   * hyphen ends the run, so {@code EU-DSGVO} offers {@code DSGVO} on its own, as a question that
   * names it bare does.
   */
  private static final Pattern WORD_RUN = Pattern.compile("[\\p{L}\\p{N}_]+");

  /** {@code BELEG_NR}, {@code Z_KASSE_ID}: uppercase segments joined by underscores. */
  private static final Pattern UPPER_SNAKE_CASE =
      Pattern.compile("[A-ZÄÖÜẞ][A-ZÄÖÜẞ0-9]*(?:_[A-ZÄÖÜẞ0-9]+)+");

  /**
   * {@code ZahlungsDatenKV}, {@code MessageRefId}: a lowercase letter directly followed by an
   * uppercase one inside the word. A capitalized word at the start of a sentence has no such
   * transition and stays an ordinary word. A Binnen-I ({@code MitarbeiterInnen}) has one and is
   * taken as well.
   */
  private static final Pattern CAMEL_CASE =
      Pattern.compile("[A-Za-zÄÖÜẞäöüß0-9]*[a-zäöüß][A-ZÄÖÜẞ][A-Za-zÄÖÜẞäöüß0-9]*");

  /**
   * {@code VORORT}, {@code INHAUS}: an all-uppercase word of at least five letters. Deliberately
   * without an exception list, so acronyms such as {@code ELSTER} are field-name lexemes as well.
   */
  private static final Pattern UPPERCASE_WORD = Pattern.compile("[A-ZÄÖÜẞ]{5,}");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

  /** At least one digit and at least one separator - see {@link #looksLikeIdentifier}. */
  private static final Pattern IDENTIFIER_SHAPE =
      Pattern.compile("(?=[A-Za-z0-9./\\-]*\\d)[A-Za-z0-9]+(?:[./\\-][A-Za-z0-9]+)+");

  private FullTextIdentifiers() {}

  /**
   * Every identifier lexeme {@code text} contains, in order of first appearance and without
   * duplicates. Never {@code null}; empty for text that carries no identifier at all, which is the
   * common case for ordinary prose. The text is read in NFC, so an umlaut decomposed into letter
   * and combining mark (common in PDF extraction) yields the same lexeme as the composed one.
   */
  public static List<String> extract(String rawText) {
    if (rawText == null || rawText.isEmpty()) {
      return List.of();
    }
    String text = Normalizer.normalize(rawText, Normalizer.Form.NFC);
    Set<String> lexemes = new LinkedHashSet<>();
    collectParagraphs(text, lexemes);
    collect(FILE_NUMBER, text, FILE_NUMBER_PREFIX, false, lexemes);
    collect(KEYWORD_FILE_NUMBER, text, FILE_NUMBER_PREFIX, true, lexemes);
    collect(STRUCTURED_FILE_NUMBER, text, FILE_NUMBER_PREFIX, true, lexemes);
    collect(ORDINANCE_NUMBER, text, ORDINANCE_NUMBER_PREFIX, false, lexemes);
    collect(EMAIL_ADDRESS, text, EMAIL_PREFIX, false, lexemes);
    collectFieldNames(text, lawAbbreviationStarts(text), lexemes);
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

  /** Start offsets of the law abbreviations {@link #LAW_OF_PARAGRAPH_REFERENCE} locates. */
  private static Set<Integer> lawAbbreviationStarts(String text) {
    Set<Integer> starts = new HashSet<>();
    Matcher matcher = LAW_OF_PARAGRAPH_REFERENCE.matcher(text);
    while (matcher.find()) {
      starts.add(matcher.start(1));
    }
    return starts;
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
   * One lexeme per technical field or element name: a word run of {@link #UPPER_SNAKE_CASE}, {@link
   * #CAMEL_CASE} or {@link #UPPERCASE_WORD} shape. Collected last, so under {@link #MAX_LEXEMES}
   * the other identifier kinds keep their place.
   *
   * <p>A law abbreviation that is part of a paragraph reference ({@code § 35 BauGB}, {@code § 35
   * Abs. 1 Nr. 4 BauGB}) yields none: the paragraph lexemes already carry it qualified, and a bare
   * {@code xfldbaugb} on the question side would match every section naming the law at the weight
   * that keeps § 34 and § 35 apart.
   */
  private static void collectFieldNames(
      String text, Set<Integer> lawAbbreviationStarts, Set<String> lexemes) {
    Matcher matcher = WORD_RUN.matcher(text);
    while (matcher.find()) {
      String word = matcher.group();
      if (lawAbbreviationStarts.contains(matcher.start())
          || word.length() > MAX_FIELD_NAME_LENGTH
          || !looksLikeFieldName(word)) {
        continue;
      }
      String normalized = normalize(transliterateUmlauts(word.toLowerCase(Locale.ROOT)));
      if (!normalized.isEmpty()) {
        lexemes.add(FIELD_NAME_PREFIX + normalized);
      }
    }
  }

  private static boolean looksLikeFieldName(String word) {
    return UPPER_SNAKE_CASE.matcher(word).matches()
        || CAMEL_CASE.matcher(word).matches()
        || UPPERCASE_WORD.matcher(word).matches();
  }

  /**
   * Keeps {@code PRÜFUNG} and {@code PRUEFUNG} on one lexeme instead of dropping the umlaut; the
   * capital {@code ẞ} arrives here already lowercased to {@code ß}.
   */
  private static String transliterateUmlauts(String lowercase) {
    return lowercase.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
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
