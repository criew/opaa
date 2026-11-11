package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.PersonThreshold;
import org.junit.jupiter.api.Test;

/**
 * The masking of {@link InventoryMetricsConfiguration} (ADR-0036): the holdings of private
 * libraries are {@code NaN} while fewer persons own one than the minimum group size and exact from
 * it on; those of shared libraries are always exact.
 */
class InventoryMetricsConfigurationTest {

  private final PersonThreshold threshold = new PersonThreshold(new GroupSizeProperties(5));

  @Test
  void privateHoldingsBelowTheMinimumGroupSizeAreNaN() {
    double[] masked = InventoryMetricsConfiguration.masked(row(4), true, threshold);

    assertThat(masked).hasSize(4).containsOnly(Double.NaN);
  }

  @Test
  void privateHoldingsFromTheMinimumGroupSizeOnAreExact() {
    assertThat(InventoryMetricsConfiguration.masked(row(5), true, threshold))
        .containsExactly(7, 120, 3_400, 1_000_000);
  }

  @Test
  void sharedHoldingsAreExactWhateverTheirOwners() {
    assertThat(InventoryMetricsConfiguration.masked(row(1), false, threshold))
        .containsExactly(7, 120, 3_400, 1_000_000);
  }

  /** Libraries, documents, chunks, bytes and the given number of owners. */
  private static double[] row(int owners) {
    return new double[] {7, 120, 3_400, 1_000_000, owners};
  }
}
