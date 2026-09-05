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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic core-field extraction (metadata-schema.md, Teil III, step 1; ADR-0024): pure
 * rules over a file name and the {@link DocumentProperties} a pipeline declared, no model, no
 * similarity. Per field the sources are tried in a fixed order and the first hit wins - see {@link
 * #TITLE_SOURCES}, {@link #documentTypeFrom} and {@link #documentDateFrom} for the order each one
 * uses, and the vocabulary and notation each source is matched against.
 *
 * <p>Three rules hold across all fields: a title is always found, falling back to the humanized
 * file name; an ambiguous source yields nothing from that source but does not stop the next one
 * from being asked; and a {@link DocumentProperties#syntheticName() synthetic name} is no naming
 * convention, so it becomes a title but never a Dokumentart or a Datum.
 */
public final class CoreMetadataExtractor {

  public static final int EXTRACTION_VERSION = 4;

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
    if (props.documentDate() != null) {
      return Optional.of(ExtractedDate.day(props.documentDate()));
    }
    Optional<ExtractedDate> heading = parseDate(props.firstHeading(), BareYearRule.ANCHORED_ONLY);
    if (heading.isPresent()) {
      return heading;
    }
    if (!props.syntheticName()) {
      Optional<ExtractedDate> fromName = parseDate(stripExtension(fileName), BareYearRule.ALLOWED);
      if (fromName.isPresent()) {
        return fromName;
      }
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
