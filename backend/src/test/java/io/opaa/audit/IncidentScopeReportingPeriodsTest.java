package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The reporting period of the disclosure is made of whole calendar months, defaults to the current
 * and the previous calendar year and ends with the last completed month; any other period is
 * refused in words.
 */
class IncidentScopeReportingPeriodsTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-17");

  @Test
  void withoutAPeriodThePreviousCalendarYearAndTheCompletedMonthsOfThisOneAreReported() {
    ReportingPeriod period = IncidentScopeReportingPeriods.of(null, null, TODAY);

    assertThat(period.from()).isEqualTo(LocalDate.parse("2025-01-01"));
    assertThat(period.to()).isEqualTo(LocalDate.parse("2026-09-30"));
  }

  @Test
  void anEndInTheRunningMonthOrLaterIsCappedToTheLastCompletedMonth() {
    assertThat(
            IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-31"), TODAY)
                .to())
        .isEqualTo(LocalDate.parse("2026-09-30"));
    assertThat(
            IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2027-03-31"), TODAY)
                .to())
        .isEqualTo(LocalDate.parse("2026-09-30"));
    assertThat(
            IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-30"),
                    LocalDate.parse("2026-10-01"))
                .to())
        .isEqualTo(LocalDate.parse("2026-09-30"));
  }

  @Test
  void aPeriodWithoutACompletedMonthIsRefused() {
    assertThatThrownBy(
            () ->
                IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("keinen abgeschlossenen Monat");
    assertThatThrownBy(
            () -> IncidentScopeReportingPeriods.of(LocalDate.parse("2026-11-01"), null, TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("keinen abgeschlossenen Monat");
  }

  @Test
  void aMissingBoundIsTheDefaultOfThatBoundAlone() {
    assertThat(IncidentScopeReportingPeriods.of(LocalDate.parse("2026-03-01"), null, TODAY).to())
        .isEqualTo(LocalDate.parse("2026-09-30"));
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
  void aPeriodBeyondThePlausibleYearsIsRefused() {
    assertThatThrownBy(
            () ->
                IncidentScopeReportingPeriods.of(
                    LocalDate.parse("-5000-01-01"), LocalDate.parse("2026-12-31"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("zwischen den Jahren 1970 und 9999");
    assertThatThrownBy(
            () ->
                IncidentScopeReportingPeriods.of(
                    LocalDate.parse("2026-01-01"), LocalDate.parse("+6000000-12-31"), TODAY))
        .isInstanceOf(ValidationException.class);
    assertThat(
            IncidentScopeReportingPeriods.of(
                    LocalDate.parse("1970-01-01"), LocalDate.parse("9999-12-31"), TODAY)
                .from())
        .isEqualTo(LocalDate.parse("1970-01-01"));
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
