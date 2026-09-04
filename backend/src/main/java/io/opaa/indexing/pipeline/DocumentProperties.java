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
 * @param firstHeading the first level-1 heading of the text, if the format has headings
 * @param frontmatter a Markdown YAML frontmatter's scalar entries, verbatim, keys lower-cased
 */
public record DocumentProperties(
    String title,
    LocalDate createdAt,
    LocalDate modifiedAt,
    LocalDate documentDate,
    String firstHeading,
    Map<String, String> frontmatter) {

  public static final DocumentProperties EMPTY =
      new DocumentProperties(null, null, null, null, null, Map.of());

  public DocumentProperties {
    title = blankToNull(title);
    firstHeading = blankToNull(firstHeading);
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
        title, createdAt, modifiedAt, documentDate, firstHeading, frontmatter);
  }

  public DocumentProperties withCreatedAt(LocalDate createdAt) {
    return new DocumentProperties(
        title, createdAt, modifiedAt, documentDate, firstHeading, frontmatter);
  }

  public DocumentProperties withModifiedAt(LocalDate modifiedAt) {
    return new DocumentProperties(
        title, createdAt, modifiedAt, documentDate, firstHeading, frontmatter);
  }

  public DocumentProperties withDocumentDate(LocalDate documentDate) {
    return new DocumentProperties(
        title, createdAt, modifiedAt, documentDate, firstHeading, frontmatter);
  }

  public DocumentProperties withFirstHeading(String firstHeading) {
    return new DocumentProperties(
        title, createdAt, modifiedAt, documentDate, firstHeading, frontmatter);
  }

  public DocumentProperties withFrontmatter(Map<String, String> frontmatter) {
    return new DocumentProperties(
        title, createdAt, modifiedAt, documentDate, firstHeading, frontmatter);
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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
