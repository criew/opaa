package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import java.time.LocalDate;

/**
 * A date with the precision it was actually read at: {@code date} is padded to the first day of the
 * month/year when only that much was known, so {@code (2024-01-01, YEAR)} means "2024".
 */
public record ExtractedDate(LocalDate date, DatePrecision precision) {

  public static ExtractedDate day(LocalDate date) {
    return new ExtractedDate(date, DatePrecision.DAY);
  }

  public static ExtractedDate month(int year, int month) {
    return new ExtractedDate(LocalDate.of(year, month, 1), DatePrecision.MONTH);
  }

  public static ExtractedDate year(int year) {
    return new ExtractedDate(LocalDate.of(year, 1, 1), DatePrecision.YEAR);
  }
}
