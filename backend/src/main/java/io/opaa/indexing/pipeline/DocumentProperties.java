package io.opaa.indexing.pipeline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The raw, uninterpreted sources a {@link DocumentPipeline} can hand to the core metadata
 * extraction (docs/features/metadata-schema.md, Teil III; ADR-0024): what the file format itself
 * declares about the document. A pipeline fills what it has cheaply at hand and never interprets -
 * every rule that turns these into a Titel, Dokumentart or Datum/Stand lives in {@code
 * io.opaa.indexing.metadata.CoreMetadataExtractor}. Blank strings normalize to {@code null}; {@code
 * frontmatter} keys are lower-cased.
 *
 * @param title the format's own title property (PDF Info Title, OOXML/ODF dc:title, HTML title)
 * @param createdAt the format's creation date property
 * @param modifiedAt the format's last-modified property - never the filesystem timestamp
 * @param documentDate a date the format declares as the document's own date (a mail's Date header);
 *     ranks above every other date source
 * @param firstHeading the first level-1 heading of the text, if the format has headings - it may
 *     sit anywhere in the document (a Markdown section, a PDF outline entry) and is therefore not
 *     the title line
 * @param titleLine the first non-blank line of the body text, reduced to that line and truncated to
 *     {@link #MAX_TITLE_LINE_LENGTH} characters here rather than by the pipeline (#1289) - the only
 *     line of the text a Dokumentart may be read from, and the reason a label line or a quotation
 *     below it can never become one
 * @param formatExtension the routed format extension of the document ({@code ".pptx"}), lower-cased
 *     - attached centrally by {@code DocumentPipelineRunner} and {@code
 *     DocumentMetadataService#reextractFromFile} from {@link
 *     DocumentPipelineSource#detectedExtension()}, never by a pipeline; {@code null} when routing
 *     resolved none
 * @param syntheticName whether the document's name is <em>not</em> a file name but free text an
 *     upstream source declared - an RSS entry's headline or a Confluence page title, in both cases
 *     its URL as a fallback (#1263, #1318). A naming convention can only be read out of a real file
 *     name; a headline names what a document is <em>about</em>.
 * @param frontmatter a Markdown YAML frontmatter's scalar entries, verbatim, keys lower-cased
 */
public record DocumentProperties(
    String title,
    LocalDate createdAt,
    LocalDate modifiedAt,
    LocalDate documentDate,
    String firstHeading,
    String titleLine,
    String formatExtension,
    boolean syntheticName,
    Map<String, String> frontmatter) {

  /**
   * Upper bound of {@link #titleLine}, in characters - a "line" a format hands over without any
   * line break in it (a PDF page of running text) is no title beyond this length.
   */
  public static final int MAX_TITLE_LINE_LENGTH = 300;

  public static final DocumentProperties EMPTY =
      new DocumentProperties(null, null, null, null, null, null, null, false, Map.of());

  public DocumentProperties {
    title = blankToNull(title);
    firstHeading = blankToNull(firstHeading);
    titleLine = truncate(DocumentTitleLine.of(titleLine));
    formatExtension = lowerCase(blankToNull(formatExtension));
    Map<String, String> normalized = new TreeMap<>();
    if (frontmatter != null) {
      frontmatter.forEach(
          (key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
              normalized.put(key.strip().toLowerCase(Locale.ROOT), value.strip());
            }
          });
    }
    frontmatter = Map.copyOf(normalized);
  }

  public DocumentProperties withTitle(String title) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  public DocumentProperties withCreatedAt(LocalDate createdAt) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  public DocumentProperties withModifiedAt(LocalDate modifiedAt) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  public DocumentProperties withDocumentDate(LocalDate documentDate) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  public DocumentProperties withFirstHeading(String firstHeading) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  public DocumentProperties withTitleLine(String titleLine) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  /** Marks the document's name as free text rather than a file name (#1263). */
  public DocumentProperties withSyntheticName(boolean syntheticName) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  public DocumentProperties withFormatExtension(String formatExtension) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  public DocumentProperties withFrontmatter(Map<String, String> frontmatter) {
    return new DocumentProperties(
        title,
        createdAt,
        modifiedAt,
        documentDate,
        firstHeading,
        titleLine,
        formatExtension,
        syntheticName,
        frontmatter);
  }

  /** A format's {@link Calendar} property (PDFBox) as the calendar's own local date. */
  public static LocalDate toLocalDate(Calendar calendar) {
    if (calendar == null) {
      return null;
    }
    return calendar.toInstant().atZone(calendar.getTimeZone().toZoneId()).toLocalDate();
  }

  /**
   * A format's {@link Date} property (POI) as a UTC calendar date - OOXML core properties are
   * stored as W3CDTF in UTC, so UTC is the only zone that reproduces the day the file declares.
   */
  public static LocalDate toLocalDate(Date date) {
    if (date == null) {
      return null;
    }
    return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
  }

  /**
   * An ISO instant string ({@link Instant#toString()} rendering, e.g. an RSS entry's {@code
   * publishedAt}) as a UTC calendar date, or {@code null} when absent or unparseable. The one
   * conversion the ingest and the backfill share for this source, so both read the same day.
   */
  public static LocalDate instantToLocalDate(String isoInstant) {
    if (isoInstant == null || isoInstant.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(isoInstant).atZone(ZoneOffset.UTC).toLocalDate();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** The {@link Instant} counterpart of {@link #instantToLocalDate(String)} - same UTC day. */
  public static LocalDate instantToLocalDate(Instant instant) {
    return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /**
   * Cut back to the last word boundary at or before {@link #MAX_TITLE_LINE_LENGTH}, never through a
   * word: a cut behind a seeded Kompositum ending would turn a fragment into a match. A title line
   * whose limit falls inside a single unbroken token has no trustworthy boundary at all and is
   * dropped.
   */
  private static String truncate(String value) {
    if (value == null || value.length() <= MAX_TITLE_LINE_LENGTH) {
      return value;
    }
    int end = MAX_TITLE_LINE_LENGTH;
    while (end > 0 && Character.isLetterOrDigit(value.charAt(end))) {
      end--;
    }
    String cut = value.substring(0, end).stripTrailing();
    return cut.isEmpty() ? null : cut;
  }

  private static String lowerCase(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
