package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The reporting period of the disclosure is made of whole calendar months and defaults to the
 * current and the previous calendar year; a count of the anlassbezogenen Klärungen below the
 * threshold never leaves exactly.
 */
class IncidentScopeCasesTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-17");

  @Test
  void withoutAPeriodTheCurrentAndThePreviousCalendarYearAreReported() {
    ReportingPeriod period = ReportingPeriod.of(null, null, TODAY);

    assertThat(period.from()).isEqualTo(LocalDate.parse("2025-01-01"));
    assertThat(period.to()).isEqualTo(LocalDate.parse("2026-12-31"));
  }

  @Test
  void aMissingBoundIsTheDefaultOfThatBoundAlone() {
    assertThat(ReportingPeriod.of(LocalDate.parse("2026-03-01"), null, TODAY).to())
        .isEqualTo(LocalDate.parse("2026-12-31"));
    assertThat(ReportingPeriod.of(null, LocalDate.parse("2025-06-30"), TODAY).from())
        .isEqualTo(LocalDate.parse("2025-01-01"));
  }

  @Test
  void aPeriodOfWholeMonthsIsTakenAsGiven() {
    ReportingPeriod period =
        ReportingPeriod.of(LocalDate.parse("2024-02-01"), LocalDate.parse("2024-02-29"), TODAY);

    assertThat(period.from()).isEqualTo(LocalDate.parse("2024-02-01"));
    assertThat(period.to()).isEqualTo(LocalDate.parse("2024-02-29"));
  }

  @Test
  void aPeriodThatCutsAMonthIsRefused() {
    assertThatThrownBy(
            () ->
                ReportingPeriod.of(
                    LocalDate.parse("2026-03-15"), LocalDate.parse("2026-04-30"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Ersten eines Monats");
    assertThatThrownBy(
            () ->
                ReportingPeriod.of(
                    LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-29"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Letzten eines Monats");
  }

  @Test
  void aPeriodStartingAfterItsEndIsRefused() {
    assertThatThrownBy(
            () ->
                ReportingPeriod.of(
                    LocalDate.parse("2026-05-01"), LocalDate.parse("2026-04-30"), TODAY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nach seinem Ende");
  }

  @Test
  void countsBelowTheThresholdLeaveOnlyAsBelowIt() {
    assertThat(CaseCount.THRESHOLD).isEqualTo(3);
    assertThat(CaseCount.of(0)).satisfies(count -> assertThat(count.count()).isZero());
    assertThat(CaseCount.of(0).belowThreshold()).isFalse();
    for (long small = 1; small < CaseCount.THRESHOLD; small++) {
      assertThat(CaseCount.of(small).count()).isNull();
      assertThat(CaseCount.of(small).belowThreshold()).isTrue();
    }
    assertThat(CaseCount.of(3).count()).isEqualTo(3L);
    assertThat(CaseCount.of(17).belowThreshold()).isFalse();
  }

  @Test
  void anExactSmallCountCannotBeBuiltAtAll() {
    assertThatThrownBy(() -> new CaseCount(1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CaseCount.of(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
