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
 *       vocabulary leaves the field empty, it never falls through), then the file name's tokens -
 *       exactly one distinct vocabulary code among them; two different ones leave the field empty.
 *   <li><b>Datum/Stand</b>: frontmatter {@code stand_datum}/{@code fassung} and the format's own
 *       document date (a mail's Date header), then the first heading (Kopfbereich), then the file
 *       name, then the modified and finally the created property. A bare year counts only as a
 *       standalone four-digit token 1900-2099.
 * </ul>
 *
 * {@link #EXTRACTION_VERSION} is raised whenever a rule here changes its output.
 */
public final class CoreMetadataExtractor {

  public static final int EXTRACTION_VERSION = 1;

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
  private static final Pattern FILE_NAME_TOKEN_SEPARATOR = Pattern.compile("[\\s_\\-.,;()\\[\\]]+");
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
    Set<String> codes = new LinkedHashSet<>();
    for (String token : fileNameTokens(fileName)) {
      vocabulary.resolve(token).ifPresent(codes::add);
    }
    return codes.size() == 1 ? Optional.of(codes.iterator().next()) : Optional.empty();
  }

  private static Optional<ExtractedDate> extractDate(String fileName, DocumentProperties props) {
    Map<String, String> frontmatter = props.frontmatter();
    Optional<ExtractedDate> declared = parseDate(unquote(frontmatter.get(FRONTMATTER_DATE)));
    if (declared.isPresent()) {
      return declared;
    }
    Optional<ExtractedDate> version = parseDate(unquote(frontmatter.get(FRONTMATTER_VERSION_YEAR)));
    if (version.isPresent()) {
      return version;
    }
    if (props.documentDate() != null) {
      return Optional.of(ExtractedDate.day(props.documentDate()));
    }
    Optional<ExtractedDate> heading = parseDate(props.firstHeading());
    if (heading.isPresent()) {
      return heading;
    }
    Optional<ExtractedDate> fromName = parseDate(stripExtension(fileName));
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
   * The first date in {@code text}, most specific notation first: ISO day, German day, ISO month,
   * German month name plus year, bare year. Empty for {@code null}, blank or dateless text, and for
   * a notation whose numbers do not form a valid calendar date.
   */
  static Optional<ExtractedDate> parseDate(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    Matcher iso = ISO_DATE.matcher(text);
    if (iso.find()) {
      return validDay(iso.group(1), iso.group(2), iso.group(3));
    }
    Matcher german = GERMAN_DATE.matcher(text);
    if (german.find()) {
      return validDay(german.group(3), german.group(2), german.group(1));
    }
    Matcher isoMonth = ISO_MONTH.matcher(text);
    if (isoMonth.find()) {
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
    Matcher year = BARE_YEAR.matcher(text);
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
