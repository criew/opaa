package io.opaa.indexing.metadata;

import io.opaa.indexing.chunk.ChunkContextTitle;
import io.opaa.indexing.format.DocumentProperties;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic core-field extraction (metadata-schema.md, Teil III, step 1; ADR-0024): pure
 * rules over a file name and the {@link DocumentProperties} a pipeline declared, no model, no
 * similarity. Per field the sources are tried in a fixed order and the first hit wins - title first
 * from frontmatter, then a title line, then the file name; document type and date follow the
 * analogous order in their own private extraction methods below.
 *
 * <p>Three rules hold across all fields: a title is always found, falling back to the humanized
 * file name; an ambiguous source yields nothing from that source but does not stop the next one
 * from being asked; and a {@link DocumentProperties#syntheticName() synthetic name} is no naming
 * convention, so it becomes a title but never a Dokumentart or a Datum.
 */
public final class CoreMetadataExtractor {

  public static final int EXTRACTION_VERSION = 5;

  static final String FRONTMATTER_TITLE = "titel";
  static final String FRONTMATTER_DOCUMENT_TYPE = "dokumentart";
  static final String FRONTMATTER_DATE = "stand_datum";
  static final String FRONTMATTER_VERSION_YEAR = "fassung";

  private static final Pattern ISO_DATE =
      Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");
  private static final Pattern GERMAN_DATE =
      Pattern.compile("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?![\\d.])");
  private static final Pattern ISO_MONTH =
      Pattern.compile("(?<![\\d-])(\\d{4})-(\\d{2})(?![\\d-])");
  private static final Pattern GERMAN_MONTH_NAME =
      Pattern.compile(
          "(?i)\\b(januar|februar|märz|maerz|april|mai|juni|juli|august|september|oktober"
              + "|november|dezember)\\s+((?:19|20)\\d{2})\\b");
  private static final Pattern BARE_YEAR =
      Pattern.compile("(?<![\\d.\\-])((?:19|20)\\d{2})(?![\\d.\\-])");
  private static final Pattern ANCHORED_YEAR =
      Pattern.compile(
          "(?i)\\b(?:stand|fassung|ausgabe|vom|version)\\s*:?\\s*((?:19|20)\\d{2})(?![\\d.\\-])");
  private static final Pattern GERMAN_LONG_DATE =
      Pattern.compile(
          "(?i)(?<![\\d.])(\\d{1,2})\\.\\s*(januar|februar|märz|maerz|april|mai|juni|juli|august"
              + "|september|oktober|november|dezember)\\s+((?:19|20)\\d{2})\\b");

  /**
   * How far into the head text a "Stand"/"Fassung" statement is still the document's own: such a
   * line belongs to the head block, below it the same word introduces another document's version.
   */
  private static final int HEAD_ANCHOR_WINDOW = 600;

  /** How much text after an anchor is searched for its date - one statement, not the next one. */
  private static final int ANCHOR_DATE_WINDOW = 40;

  /**
   * Upper length of a title line that may stand in for a heading - beyond it, it is a paragraph.
   */
  private static final int MAX_HEADING_LIKE_LENGTH = 120;

  /** The caption under which a Vordruck states what it is about. */
  private static final Pattern SUBJECT_LABEL =
      Pattern.compile("(?i)^(betreff|gegenstand|thema)\\s*:(\\s|$)");

  /** How far below a line such a caption still belongs to it. */
  private static final int LABEL_BLOCK_LINES = 10;

  /** The head block's own version statement, immediately followed by its date. */
  private static final Pattern STAND_ANCHOR =
      Pattern.compile(
          "(?i)\\b(?:stand|fassung|ausgabe|gültig\\s+ab|gueltig\\s+ab)\\b"
              + "\\s*(?:vom|am|ab)?\\s*:?\\s*");

  /**
   * The self-designating Inkrafttretensklausel ("Diese Satzung tritt am 1. Januar 2026 in Kraft").
   * The demonstrative is what makes it a statement about <em>this</em> document; a clause about a
   * law that came into force ("die zum 23.5.2021 in Kraft getreten sind") is a reference and is not
   * matched. It sits wherever the document's closing provisions sit, not in the first lines.
   */
  private static final Pattern ENTRY_INTO_FORCE =
      Pattern.compile(
          "(?i)\\bdies(?:e|er|es)\\s+\\p{L}+\\s+tritt\\s+(?:\\p{L}+\\s+)?(?:am|zum)\\s+"
              + "([\\s\\S]{4,40}?)\\s+in\\s+Kraft");

  /** A year standing right behind an anchor word - the date of that statement. */
  private static final Pattern LEADING_YEAR = Pattern.compile("((?:19|20)\\d{2})(?![\\d.\\-])");

  /** Whether a bare four-digit year is a credible date in the text being scanned. */
  private enum BareYearRule {
    /** A file name or a frontmatter value: a standalone year is a naming convention. */
    ALLOWED,
    /** Free heading text: a year needs an anchor word, an unanchored number is not a date. */
    ANCHORED_ONLY,
    /** The window behind an anchor: the anchor is already given, only its notation is read. */
    FORBIDDEN
  }

  private static final Pattern FILE_NAME_TOKEN_SEPARATOR = Pattern.compile("[\\s_\\-.,;()\\[\\]]+");

  /** Word boundaries in running text: everything that is not a letter or a digit separates. */
  private static final Pattern TEXT_TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

  /**
   * Formats whose Dokumentart follows from the format alone - a presentation file is a
   * Praesentation, there is nothing else it could be. Consulted last, so every text source still
   * outranks it. No entry for PDF/DOCX: those carry every Dokumentart there is.
   */
  private static final Map<String, String> DOCUMENT_TYPE_BY_EXTENSION =
      Map.of(".pptx", "PRAESENTATION", ".odp", "PRAESENTATION");

  private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");

  private static final List<String> MONTH_NAMES =
      List.of(
          "januar",
          "februar",
          "märz",
          "april",
          "mai",
          "juni",
          "juli",
          "august",
          "september",
          "oktober",
          "november",
          "dezember");

  private CoreMetadataExtractor() {}

  public static ExtractedCoreMetadata extract(
      String fileName, DocumentProperties properties, DocumentTypeVocabulary vocabulary) {
    DocumentProperties props = properties == null ? DocumentProperties.EMPTY : properties;
    String name = fileName == null ? "" : fileName;
    return new ExtractedCoreMetadata(
        extractTitle(name, props),
        extractDocumentType(name, props, vocabulary),
        extractDate(name, props));
  }

  private static Optional<String> extractTitle(String fileName, DocumentProperties props) {
    // A synthetic name is the headline an upstream source declared, and the format's title is that
    // same headline - there is no tool in between, and no file name it could be repeating.
    if (props.title() != null
        && (props.syntheticName() || !ToolTitle.matches(props.title(), props.formatExtension()))) {
      return Optional.of(props.title());
    }
    String frontmatterTitle = props.frontmatter().get(FRONTMATTER_TITLE);
    if (frontmatterTitle != null) {
      return Optional.of(unquote(frontmatterTitle));
    }
    if (props.firstHeading() != null) {
      return Optional.of(props.firstHeading());
    }
    if (isHeadingLike(props)) {
      return Optional.of(props.titleLine());
    }
    if (fileName.isBlank()) {
      return Optional.empty();
    }
    String humanized = ChunkContextTitle.deriveTitle(fileName);
    return humanized.isBlank() ? Optional.empty() : Optional.of(humanized);
  }

  /**
   * Whether a title line may stand in for a missing heading: a short line that is neither a
   * sentence nor a label, and not the letterhead of a form. A document that opens with running text
   * or with a letterhead has no heading at all, and its file name - chosen by a person - names it
   * better than its first line does.
   */
  private static boolean isHeadingLike(DocumentProperties props) {
    String titleLine = props.titleLine();
    if (titleLine == null || titleLine.length() > MAX_HEADING_LIKE_LENGTH) {
      return false;
    }
    char last = titleLine.charAt(titleLine.length() - 1);
    if (last == '.' || last == '!' || last == '?' || last == ':' || last == ';' || last == ',') {
      return false;
    }
    // Both marks together, never one alone: a Satzung's heading is regularly set in capitals, and
    // a form's own heading regularly stands above a field block. Only their combination is the
    // letterhead of a Vordruck, whose subject stands in a labelled field further down.
    return !(isLetterhead(titleLine) && subjectLabelFollows(props.headText(), titleLine));
  }

  /**
   * A line in capitals only ("STADT RHEINFURT") - the notation of a letterhead. {@code ß} counts as
   * a lower-case letter, so a heading containing it is never read as one.
   */
  private static boolean isLetterhead(String line) {
    boolean hasLetter = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (Character.isLetter(c)) {
        hasLetter = true;
        if (Character.isLowerCase(c)) {
          return false;
        }
      }
    }
    return hasLetter;
  }

  /**
   * Whether a label naming the document's subject ("Betreff:", "Gegenstand:", "Thema:") follows
   * {@code titleLine} within the next {@link #LABEL_BLOCK_LINES} lines: then the document says
   * itself where its subject stands, and it is not this line. An ordinary field block ("Name:",
   * "Gremium:") says nothing of the kind - a form's heading regularly stands above one.
   */
  private static boolean subjectLabelFollows(String headText, String titleLine) {
    if (headText == null) {
      return false;
    }
    int start = headText.indexOf(titleLine);
    if (start < 0) {
      return false;
    }
    String[] lines = headText.substring(start + titleLine.length()).split("\\R");
    for (int i = 0; i < Math.min(lines.length, LABEL_BLOCK_LINES); i++) {
      if (SUBJECT_LABEL.matcher(lines[i].strip()).find()) {
        return true;
      }
    }
    return false;
  }

  private static Optional<String> extractDocumentType(
      String fileName, DocumentProperties props, DocumentTypeVocabulary vocabulary) {
    String declared = props.frontmatter().get(FRONTMATTER_DOCUMENT_TYPE);
    if (declared != null) {
      return vocabulary.resolve(unquote(declared));
    }
    if (!props.syntheticName()) {
      Optional<String> fromFileName =
          singleCode(fileNameTokens(fileName), vocabulary::resolveToken);
      if (fromFileName.isPresent()) {
        return fromFileName;
      }
    }
    Optional<String> fromTitleLine = fromTitleLine(props, vocabulary);
    if (fromTitleLine.isPresent()) {
      return fromTitleLine;
    }
    return fromFormat(props.formatExtension(), vocabulary);
  }

  /**
   * The one code {@code tokens} agree on under {@code match}, or empty when none matches or two
   * different ones do - "lieber leer als geraten" applied per source, not across sources.
   */
  private static Optional<String> singleCode(
      List<String> tokens, Function<String, Optional<String>> match) {
    Set<String> codes = new LinkedHashSet<>();
    for (String token : tokens) {
      match.apply(token).ifPresent(codes::add);
    }
    return codes.size() == 1 ? Optional.of(codes.iterator().next()) : Optional.empty();
  }

  /**
   * The Dokumentart of the document's title line - the first line of its text, or its first heading
   * when the format has no text line. Nothing else is read: only the title line is a
   * self-designation. Matched exactly, never through a Kompositum ending, since a title too is full
   * of compounds that are no Dokumentart ("Tagesordnung"). A first heading that is not the title
   * line supplies no code of its own but does veto a differing one - lieber leer als geraten.
   */
  private static Optional<String> fromTitleLine(
      DocumentProperties props, DocumentTypeVocabulary vocabulary) {
    String heading = props.firstHeading();
    String titleLine = props.titleLine() != null ? props.titleLine() : heading;
    if (titleLine == null) {
      return Optional.empty();
    }
    Optional<String> code = singleCode(textTokens(titleLine), vocabulary::resolve);
    if (heading == null || heading.equals(titleLine)) {
      return code;
    }
    Optional<String> fromHeading = singleCode(textTokens(heading), vocabulary::resolve);
    return fromHeading.isPresent() && !fromHeading.equals(code) ? Optional.empty() : code;
  }

  private static Optional<String> fromFormat(
      String formatExtension, DocumentTypeVocabulary vocabulary) {
    if (formatExtension == null) {
      return Optional.empty();
    }
    String code = DOCUMENT_TYPE_BY_EXTENSION.get(formatExtension);
    return code != null && vocabulary.containsCode(code) ? Optional.of(code) : Optional.empty();
  }

  private static Optional<ExtractedDate> extractDate(String fileName, DocumentProperties props) {
    Map<String, String> frontmatter = props.frontmatter();
    Optional<ExtractedDate> declared =
        parseDate(unquote(frontmatter.get(FRONTMATTER_DATE)), BareYearRule.ALLOWED);
    if (declared.isPresent()) {
      return declared;
    }
    Optional<ExtractedDate> version =
        parseDate(unquote(frontmatter.get(FRONTMATTER_VERSION_YEAR)), BareYearRule.ALLOWED);
    if (version.isPresent()) {
      return version;
    }
    if (GeneratorDefaultDate.isWithinPlausibleRange(props.documentDate())) {
      return Optional.of(ExtractedDate.day(props.documentDate()));
    }
    Optional<ExtractedDate> heading = parseDate(props.firstHeading(), BareYearRule.ANCHORED_ONLY);
    if (heading.isPresent()) {
      return heading;
    }
    if (!props.syntheticName()) {
      // Text an upstream source declared is no self-designation either: a press release names the
      // Satzung it reports about, and would inherit its Inkrafttretensdatum.
      Optional<ExtractedDate> fromHead = headTextDate(props.headText());
      if (fromHead.isPresent()) {
        return fromHead;
      }
      Optional<ExtractedDate> fromName = parseDate(stripExtension(fileName), BareYearRule.ALLOWED);
      if (fromName.isPresent()) {
        return fromName;
      }
    }
    if (GeneratorDefaultDate.isPlausible(props.modifiedAt())) {
      return Optional.of(ExtractedDate.day(props.modifiedAt()));
    }
    if (GeneratorDefaultDate.isPlausible(props.createdAt())) {
      return Optional.of(ExtractedDate.day(props.createdAt()));
    }
    return Optional.empty();
  }

  /**
   * The document's own date from its text, and only from an anchored statement: a "Stand"/"Fassung"
   * line within the head block ({@link #HEAD_ANCHOR_WINDOW} characters), else the <b>latest</b>
   * self-designating Inkrafttretensklausel, which by drafting convention sits in the closing
   * provisions. Everything else in the running text - a deadline, an amount, a year in a sentence -
   * is no Datum/Stand.
   */
  private static Optional<ExtractedDate> headTextDate(String headText) {
    if (headText == null) {
      return Optional.empty();
    }
    String head = headText.substring(0, Math.min(HEAD_ANCHOR_WINDOW, headText.length()));
    Matcher anchor = STAND_ANCHOR.matcher(head);
    while (anchor.find()) {
      String after =
          head.substring(anchor.end(), Math.min(anchor.end() + ANCHOR_DATE_WINDOW, head.length()));
      Optional<ExtractedDate> date = parseDate(after, BareYearRule.FORBIDDEN);
      if (date.isPresent()) {
        return date;
      }
      // A bare year counts only immediately behind the anchor ("Stand: 2024"); further into the
      // window it is part of a phrase ("Stand der Technik 2019"), not the statement's date.
      Matcher year = LEADING_YEAR.matcher(after);
      if (year.lookingAt()) {
        return Optional.of(ExtractedDate.year(Integer.parseInt(year.group(1))));
      }
    }
    // The latest clause wins: a Lesefassung carries the original statute's clause and the one of
    // every amending statute, and the youngest of them is the version in force.
    Optional<ExtractedDate> latest = Optional.empty();
    Matcher entryIntoForce = ENTRY_INTO_FORCE.matcher(headText);
    while (entryIntoForce.find()) {
      Optional<ExtractedDate> date = parseDate(entryIntoForce.group(1), BareYearRule.ALLOWED);
      if (date.isPresent()
          && (latest.isEmpty() || date.get().date().isAfter(latest.get().date()))) {
        latest = date;
      }
    }
    return latest;
  }

  /**
   * The first valid date in {@code text}, most specific notation first: ISO day, German day, ISO
   * month, German month name plus year, then a year (bare or anchored, per {@code bareYearRule}).
   * Every candidate of a notation is tried before the next notation; a candidate whose numbers do
   * not form a calendar date is skipped, never the end of the search.
   */
  static Optional<ExtractedDate> parseDate(String text, BareYearRule bareYearRule) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    Matcher iso = ISO_DATE.matcher(text);
    while (iso.find()) {
      Optional<ExtractedDate> day = validDay(iso.group(1), iso.group(2), iso.group(3));
      if (day.isPresent()) {
        return day;
      }
    }
    Matcher german = GERMAN_DATE.matcher(text);
    while (german.find()) {
      Optional<ExtractedDate> day = validDay(german.group(3), german.group(2), german.group(1));
      if (day.isPresent()) {
        return day;
      }
    }
    Matcher longDate = GERMAN_LONG_DATE.matcher(text);
    while (longDate.find()) {
      Optional<ExtractedDate> day =
          validDay(
              longDate.group(3), String.valueOf(monthNumber(longDate.group(2))), longDate.group(1));
      if (day.isPresent()) {
        return day;
      }
    }
    Matcher isoMonth = ISO_MONTH.matcher(text);
    while (isoMonth.find()) {
      int month = Integer.parseInt(isoMonth.group(2));
      if (month >= 1 && month <= 12) {
        return Optional.of(ExtractedDate.month(Integer.parseInt(isoMonth.group(1)), month));
      }
    }
    Matcher monthName = GERMAN_MONTH_NAME.matcher(text);
    if (monthName.find()) {
      return Optional.of(
          ExtractedDate.month(
              Integer.parseInt(monthName.group(2)), monthNumber(monthName.group(1))));
    }
    if (bareYearRule == BareYearRule.FORBIDDEN) {
      return Optional.empty();
    }
    Matcher year = (bareYearRule == BareYearRule.ALLOWED ? BARE_YEAR : ANCHORED_YEAR).matcher(text);
    if (year.find()) {
      return Optional.of(ExtractedDate.year(Integer.parseInt(year.group(1))));
    }
    return Optional.empty();
  }

  /** The 1-based number of a German month name, both spellings of "März" included. */
  private static int monthNumber(String monthName) {
    String word = monthName.toLowerCase(Locale.GERMAN).replace("maerz", "märz");
    return MONTH_NAMES.indexOf(word) + 1;
  }

  private static Optional<ExtractedDate> validDay(String year, String month, String day) {
    try {
      return Optional.of(
          ExtractedDate.day(
              LocalDate.of(
                  Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day))));
    } catch (DateTimeException e) {
      return Optional.empty();
    }
  }

  private static List<String> fileNameTokens(String fileName) {
    String base = stripExtension(fileName);
    return List.of(FILE_NAME_TOKEN_SEPARATOR.split(base)).stream()
        .filter(token -> !token.isBlank())
        .toList();
  }

  private static List<String> textTokens(String text) {
    if (text.isBlank()) {
      return List.of();
    }
    return List.of(TEXT_TOKEN_SEPARATOR.split(text)).stream()
        .filter(token -> !token.isBlank())
        .toList();
  }

  private static String stripExtension(String fileName) {
    Matcher matcher = EXTENSION.matcher(fileName);
    return matcher.find() ? fileName.substring(0, matcher.start()) : fileName;
  }

  private static String unquote(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    if (stripped.length() >= 2
        && ((stripped.startsWith("\"") && stripped.endsWith("\""))
            || (stripped.startsWith("'") && stripped.endsWith("'")))) {
      return stripped.substring(1, stripped.length() - 1).strip();
    }
    return stripped;
  }
}
