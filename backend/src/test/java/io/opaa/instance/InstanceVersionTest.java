package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The rolling-update window of ADR-0053, Entscheidung 4. */
class InstanceVersionTest {

  @Test
  void theSameVersionAlwaysRunsSideBySide() {
    InstanceVersion version = new InstanceVersion("1.4.0", "1.4.0");

    assertThat(version.compatibleWith(new InstanceVersion("1.4.0", "1.4.0"))).isTrue();
  }

  @Test
  void aNewerVersionRunsNextToTheOldestVersionItNamesAndNothingOlder() {
    InstanceVersion newer = new InstanceVersion("1.5.0", "1.4.0");

    assertThat(newer.compatibleWith(new InstanceVersion("1.4.0", "1.3.0"))).isTrue();
    assertThat(newer.compatibleWith(new InstanceVersion("1.3.2", "1.3.0"))).isFalse();
  }

  @Test
  void theWindowOfTheNewerVersionDecidesFromEitherSide() {
    InstanceVersion older = new InstanceVersion("1.4.0", "1.3.0");

    assertThat(older.compatibleWith(new InstanceVersion("1.5.0", "1.4.0"))).isTrue();
    assertThat(older.compatibleWith(new InstanceVersion("1.6.0", "1.5.0"))).isFalse();
  }

  @Test
  void aVersionBoundaryWithoutRollingUpdateRunsNextToItselfOnly() {
    InstanceVersion boundary = new InstanceVersion("2.0.0", "2.0.0");

    assertThat(boundary.compatibleWith(new InstanceVersion("1.9.0", "1.8.0"))).isFalse();
  }

  @Test
  void ordersPreReleasesBeforeTheirRelease() {
    assertThat(InstanceVersion.compare("0.1.0-rc.2", "0.1.0-rc.10")).isNegative();
    assertThat(InstanceVersion.compare("0.1.0-rc.4", "0.1.0")).isNegative();
    assertThat(InstanceVersion.compare("0.0.0-dev", "0.0.0-dev")).isZero();
    assertThat(InstanceVersion.compare("1.10.0", "1.9.3")).isPositive();
  }

  @Test
  void rejectsAWindowThatLiesAboveTheVersion() {
    assertThatThrownBy(() -> new InstanceVersion("1.4.0", "1.5.0"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thisBuildRunsNextToItselfOnlyUntilAReleaseNamesAWindow() {
    assertThat(InstanceVersion.of("0.0.0-dev").oldestCompatible()).isEqualTo("0.0.0-dev");
  }
}
