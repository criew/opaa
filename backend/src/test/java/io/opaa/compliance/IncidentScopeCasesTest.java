package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * A reporting period of the disclosure is whole calendar months; a count of the anlassbezogenen
 * Klärungen below the threshold never leaves exactly.
 */
class IncidentScopeCasesTest {

  @Test
  void aPeriodOfWholeMonthsIsHeld() {
    ReportingPeriod period =
        new ReportingPeriod(LocalDate.parse("2024-02-01"), LocalDate.parse("2024-02-29"));

    assertThat(period.from()).isEqualTo(LocalDate.parse("2024-02-01"));
    assertThat(period.to()).isEqualTo(LocalDate.parse("2024-02-29"));
  }

  @Test
  void aPeriodThatCutsAMonthOrIsReversedCannotBeBuilt() {
    assertThatThrownBy(
            () -> new ReportingPeriod(LocalDate.parse("2026-03-15"), LocalDate.parse("2026-04-30")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReportingPeriod(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-29")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReportingPeriod(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-04-30")))
        .isInstanceOf(IllegalArgumentException.class);
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
