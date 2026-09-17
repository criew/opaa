package io.opaa.indexing.metadata;

import java.time.LocalDate;
import java.util.Set;

/**
 * Whether a date a file format declares about itself can be read as the document's Datum/Stand.
 *
 * <p>A file written by a document generator carries the generator's own template date rather than
 * the document's: {@code python-docx} stamps 2013-12-23, {@code python-pptx} 2013-01-27, ReportLab
 * 2000-01-01 in its reproducible mode. Such a date is "keine Angabe", never a Stand - and it is the
 * most damaging kind of wrong value, because it looks like a properly filled field.
 */
final class GeneratorDefaultDate {

  /**
   * The oldest year a file property may claim. Below it lie the epoch dates a format or a ZIP
   * container substitutes for a missing timestamp (1601 Windows FILETIME, 1970 Unix, 1980 ZIP); no
   * electronically authored document of an administration predates it.
   */
  static final int MIN_YEAR = 1990;

  /** Template dates of the generators this system meets; each one is a constant, not a document. */
  private static final Set<LocalDate> TEMPLATE_DATES =
      Set.of(
          LocalDate.of(2013, 12, 23),
          LocalDate.of(2013, 1, 27),
          LocalDate.of(2000, 1, 1),
          LocalDate.of(1980, 1, 1),
          LocalDate.of(1970, 1, 1),
          LocalDate.of(1601, 1, 1));

  private GeneratorDefaultDate() {}

  /** Whether {@code date} is a date of the document rather than of the tool that wrote it. */
  static boolean isPlausible(LocalDate date) {
    return date != null && date.getYear() >= MIN_YEAR && !TEMPLATE_DATES.contains(date);
  }

  /**
   * Whether {@code date} is at least in the plausible range - the only check a date the format
   * declares as the <em>document's own</em> (a mail's {@code Date} header, a feed entry's
   * publication date) is subjected to: a mail genuinely sent on 2000-01-01 is not a template date.
   */
  static boolean isWithinPlausibleRange(LocalDate date) {
    return date != null && date.getYear() >= MIN_YEAR;
  }
}
