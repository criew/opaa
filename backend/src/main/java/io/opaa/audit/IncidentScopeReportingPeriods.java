package io.opaa.audit;

import io.opaa.common.ValidationException;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * The reporting period a caller of the disclosure asked for, checked and completed: a missing start
 * is 1 January of the year before {@code today}, a missing end 31 December of the year of {@code
 * today}. The end is then capped to the last completed month, so the running month - whose counts
 * change within minutes of a request - never leaves.
 */
final class IncidentScopeReportingPeriods {

  /**
   * Plausibility bounds of a period: no case predates 1970, no period reaches past 9999; they also
   * keep every date inside the range of PostgreSQL's {@code date}.
   */
  static final int MIN_YEAR = 1970;

  static final int MAX_YEAR = 9999;

  private IncidentScopeReportingPeriods() {}

  /**
   * @throws ValidationException when the period lies outside {@link #MIN_YEAR} to {@link
   *     #MAX_YEAR}, does not start on the first or end on the last day of a month, starts after it
   *     ends or holds no completed month
   */
  static ReportingPeriod of(LocalDate from, LocalDate to, LocalDate today) {
    LocalDate start = from != null ? from : LocalDate.of(today.getYear() - 1, 1, 1);
    LocalDate end = to != null ? to : LocalDate.of(today.getYear(), 12, 31);
    if (start.getYear() < MIN_YEAR || end.getYear() > MAX_YEAR) {
      throw new ValidationException(
          "Der Berichtszeitraum liegt zwischen den Jahren " + MIN_YEAR + " und " + MAX_YEAR);
    }
    if (start.getDayOfMonth() != 1) {
      throw new ValidationException("Der Berichtszeitraum beginnt am Ersten eines Monats");
    }
    if (!end.equals(end.with(TemporalAdjusters.lastDayOfMonth()))) {
      throw new ValidationException("Der Berichtszeitraum endet am Letzten eines Monats");
    }
    if (start.isAfter(end)) {
      throw new ValidationException("Der Berichtszeitraum beginnt nach seinem Ende");
    }
    LocalDate lastCompleted = today.withDayOfMonth(1).minusDays(1);
    if (end.isAfter(lastCompleted)) {
      end = lastCompleted;
    }
    if (start.isAfter(end)) {
      throw new ValidationException(
          "Der Berichtszeitraum enthält keinen abgeschlossenen Monat; der laufende Monat wird nicht"
              + " ausgewiesen");
    }
    return new ReportingPeriod(start, end);
  }
}
