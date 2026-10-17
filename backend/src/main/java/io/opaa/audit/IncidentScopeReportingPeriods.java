package io.opaa.audit;

import io.opaa.common.ValidationException;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * The reporting period a caller of the disclosure asked for, checked and completed: a missing start
 * is 1 January of the year before {@code today}, a missing end 31 December of the year of {@code
 * today}, together the current and the previous calendar year.
 */
final class IncidentScopeReportingPeriods {

  private IncidentScopeReportingPeriods() {}

  /**
   * @throws ValidationException when the period does not start on the first or end on the last day
   *     of a month, or starts after it ends
   */
  static ReportingPeriod of(LocalDate from, LocalDate to, LocalDate today) {
    LocalDate start = from != null ? from : LocalDate.of(today.getYear() - 1, 1, 1);
    LocalDate end = to != null ? to : LocalDate.of(today.getYear(), 12, 31);
    if (start.getDayOfMonth() != 1) {
      throw new ValidationException("Der Berichtszeitraum beginnt am Ersten eines Monats");
    }
    if (!end.equals(end.with(TemporalAdjusters.lastDayOfMonth()))) {
      throw new ValidationException("Der Berichtszeitraum endet am Letzten eines Monats");
    }
    if (start.isAfter(end)) {
      throw new ValidationException("Der Berichtszeitraum beginnt nach seinem Ende");
    }
    return new ReportingPeriod(start, end);
  }
}
