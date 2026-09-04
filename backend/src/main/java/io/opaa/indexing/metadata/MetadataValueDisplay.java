package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** The one German display form of a document date, shared by Beleg, metadata view and audit. */
public final class MetadataValueDisplay {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

  private MetadataValueDisplay() {}

  /** "12.03.2026", "03/2026" or "2024" - a date at its own precision, never a padded day. */
  public static String displayDate(LocalDate date, DatePrecision precision) {
    if (precision == null) {
      return DAY.format(date);
    }
    return switch (precision) {
      case DAY -> DAY.format(date);
      case MONTH -> MONTH.format(date);
      case YEAR -> Integer.toString(date.getYear());
    };
  }
}
