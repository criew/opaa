package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The reporting period of the disclosure is made of whole calendar months and defaults to the
 * current and the previous calendar year; any other period is refused in words.
 */
class IncidentScopeReportingPeriodsTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-17");

  @Test
  void withoutAPeriodTheCurrentAndThePreviousCalendarYearAreReported() {
    ReportingPeriod period = IncidentScopeReportingPeriods.of(null, null, TODAY);

    assertThat(period.from()).isEqualTo(LocalDate.parse("2025-01-01"));
    assertThat(period.to()).isEqualTo(LocalDate.parse("2026-12-31"));
  }

  @Test
  void aMissingBoundIsTheDefaultOfThatBoundAlone() {
    assertThat(IncidentScopeReportingPeriods.of(LocalDate.parse("2026-03-01"), null, TODAY).to())
        .isEqualTo(LocalDate.parse("2026-12-31"));
    assertThat(IncidentScopeReportingPeriods.of(null, LocalDate.parse("2025-06-30"), TODAY).from())
        .isEqualTo(LocalDate.parse("2025-01-01"));
  }

  @Test
  void aPeriodOfWholeMonthsIsTakenAsGiven() {
    ReportingPeriod period =
        IncidentScopeReportingPeriods.of(
            LocalDate.parse("2024-02-01"), LocalDate.parse("2024-02-29"), TODAY);

    assertThat(period.from()).isEqualTo(LocalDate.parse("2024-02-01"));
    assertThat(period.to()).isEqualTo(LocalDate.parse("2024-02-29"));
  }

  @Test
  void aPeriodThatCutsAMonthIsRefused() {
    assertThatThrownBy(
            () ->
                IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-03-15"), LocalDate.parse("2026-04-30"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Ersten eines Monats");
    assertThatThrownBy(
            () ->
                IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-29"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Letzten eines Monats");
  }

  @Test
  void aPeriodStartingAfterItsEndIsRefused() {
    assertThatThrownBy(
            () ->
                IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-05-01"), LocalDate.parse("2026-04-30"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nach seinem Ende");
  }
}
