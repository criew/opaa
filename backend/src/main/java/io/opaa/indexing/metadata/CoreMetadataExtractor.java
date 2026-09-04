package io.opaa.indexing.metadata;

import io.opaa.indexing.ChunkContextTitle;
import io.opaa.indexing.pipeline.DocumentProperties;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic core-field extraction (metadata-schema.md, Teil III, step 1; ADR-0024): pure
 * rules over a file name and the {@link DocumentProperties} a pipeline declared, no model, no
 * similarity. Source order per field, first hit wins:
 *
 * <ul>
 *   <li><b>Titel</b>: format title property, frontmatter {@code titel}, first level-1 heading, the
 *       humanized file name ({@link ChunkContextTitle}) - so a title is always found.
 *   <li><b>Dokumentart</b>: frontmatter {@code dokumentart} (an explicit declaration outside the
 *       vocabulary leaves the field empty, it never falls through), then the file name's tokens,
 *       then the Kopfbereich ({@link DocumentProperties#firstHeading()} plus {@link
 *       DocumentProperties#headText()}), then the file format (#1263). Within one of the three
 *       token sources exactly one distinct code must result: a token matches a vocabulary term
 *       exactly or carries one of its seeded Kompositum endings, and two different codes at once
 *       yield nothing <em>from that source</em> - the next source is still asked, unlike for the
 *       frontmatter declaration.
 *   <li><b>Datum/Stand</b>: frontmatter {@code stand_datum}/{@code fassung} and the format's own
 *       document date (a mail's Date header), then the first heading (Kopfbereich), then the file
 *       name, then the modified and finally the created property. Within one source every candidate
 *       of a notation is tried, an impossible calendar date (an Aktenzeichen that looks like one)
 *       is skipped. A bare year 1900-2099 counts only in a file name or frontmatter value; in
 *       heading text it needs an anchor ({@code Stand 2026}, {@code Fassung 2024}) - an unanchored
 *       number there is an amount or a paragraph, never a Stand.
 * </ul>
 *
 * {@link #EXTRACTION_VERSION} is raised whenever a rule here changes its output.
 */
public final class CoreMetadataExtractor {

  public static final int EXTRACTION_VERSION = 2;

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

  /** Whether a bare four-digit year is a credible date in the text being scanned. */
  private enum BareYearRule {
    /** A file name or a frontmatter value: a standalone year is a naming convention. */
    ALLOWED,
    /** Free heading text: a year needs an anchor word, an unanchored number is not a date. */
    ANCHORED_ONLY
  }

  private static final Pattern FILE_NAME_TOKEN_SEPARATOR = Pattern.compile("[\\s_\\-.,;()\\[\\]]+");

  /** Word boundaries in running text: everything that is not a letter or a digit separates. */
  private static final Pattern TEXT_TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

  /**
   * Formats whose Dokumentart follows from the format alone (#1263) - a presentation file is a
   * Praesentation, there is nothing else it could be. Consulted last, so every text source still
   * outranks it. No entry for PDF/DOCX: those carry every Dokumentart there is.
   */
  private static final Map<String, String> DOCUMENT_TYPE_BY_EXTENSION =
      Map.of(
          ".pptx", "PRAESENTATION",
          ".ppt", "PRAESENTATION",
          ".odp", "PRAESENTATION");

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
    if (props.title() != null) {
      return Optional.of(props.title());
    }
    String frontmatterTitle = props.frontmatter().get(FRONTMATTER_TITLE);
    if (frontmatterTitle != null) {
      return Optional.of(unquote(frontmatterTitle));
    }
    if (props.firstHeading() != null) {
      return Optional.of(props.firstHeading());
    }
    if (fileName.isBlank()) {
      return Optional.empty();
    }
    String humanized = ChunkContextTitle.deriveTitle(fileName);
    return humanized.isBlank() ? Optional.empty() : Optional.of(humanized);
  }

  private static Optional<String> extractDocumentType(
      String fileName, DocumentProperties props, DocumentTypeVocabulary vocabulary) {
    String declared = props.frontmatter().get(FRONTMATTER_DOCUMENT_TYPE);
    if (declared != null) {
      return vocabulary.resolve(unquote(declared));
    }
    Optional<String> fromFileName = singleCode(fileNameTokens(fileName), vocabulary);
    if (fromFileName.isPresent()) {
      return fromFileName;
    }
    Optional<String> fromHead = singleCode(headTokens(props), vocabulary);
    if (fromHead.isPresent()) {
      return fromHead;
    }
    return fromFormat(props.formatExtension(), vocabulary);
  }

  /**
   * The one code {@code tokens} agree on, or empty when none matches or two different ones do -
   * "lieber leer als geraten" applied per source, not across sources.
   */
  private static Optional<String> singleCode(
      List<String> tokens, DocumentTypeVocabulary vocabulary) {
    Set<String> codes = new LinkedHashSet<>();
    for (String token : tokens) {
      vocabulary.resolveToken(token).ifPresent(codes::add);
    }
    return codes.size() == 1 ? Optional.of(codes.iterator().next()) : Optional.empty();
  }

  /**
   * The words of the Kopfbereich: the first heading and the opening of the body text, which {@link
   * DocumentProperties} has already cut to its head - a word further down the document is never
   * seen here, and can never become a Dokumentart.
   */
  private static List<String> headTokens(DocumentProperties props) {
    StringBuilder head = new StringBuilder();
    if (props.firstHeading() != null) {
      head.append(props.firstHeading()).append('\n');
    }
    if (props.headText() != null) {
      head.append(props.headText());
    }
    return textTokens(head.toString());
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
    if (props.documentDate() != null) {
      return Optional.of(ExtractedDate.day(props.documentDate()));
    }
    Optional<ExtractedDate> heading = parseDate(props.firstHeading(), BareYearRule.ANCHORED_ONLY);
    if (heading.isPresent()) {
      return heading;
    }
    Optional<ExtractedDate> fromName = parseDate(stripExtension(fileName), BareYearRule.ALLOWED);
    if (fromName.isPresent()) {
      return fromName;
    }
    if (props.modifiedAt() != null) {
      return Optional.of(ExtractedDate.day(props.modifiedAt()));
    }
    if (props.createdAt() != null) {
      return Optional.of(ExtractedDate.day(props.createdAt()));
    }
    return Optional.empty();
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
    Matcher isoMonth = ISO_MONTH.matcher(text);
    while (isoMonth.find()) {
      int month = Integer.parseInt(isoMonth.group(2));
      if (month >= 1 && month <= 12) {
        return Optional.of(ExtractedDate.month(Integer.parseInt(isoMonth.group(1)), month));
      }
    }
    Matcher monthName = GERMAN_MONTH_NAME.matcher(text);
    if (monthName.find()) {
      String monthWord = monthName.group(1).toLowerCase(Locale.GERMAN).replace("maerz", "märz");
      int month = MONTH_NAMES.indexOf(monthWord) + 1;
      return Optional.of(ExtractedDate.month(Integer.parseInt(monthName.group(2)), month));
    }
    Matcher year = (bareYearRule == BareYearRule.ALLOWED ? BARE_YEAR : ANCHORED_YEAR).matcher(text);
    if (year.find()) {
      return Optional.of(ExtractedDate.year(Integer.parseInt(year.group(1))));
    }
    return Optional.empty();
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
