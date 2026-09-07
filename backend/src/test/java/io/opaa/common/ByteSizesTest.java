package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link ByteSizes#format}: the unit boundaries, the one-decimal cap, the German separators and the
 * TB ceiling - the same figures the frontend's {@code formatFileSize} renders.
 */
class ByteSizesTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "0; 0 B",
        "1023; 1023 B",
        "1024; 1 KB",
        "2048; 2 KB",
        "1536; 1,5 KB",
        "1048575; 1.024 KB",
        "1048576; 1 MB",
        "1572864; 1,5 MB",
        "734003200; 700 MB",
        "3221225472; 3 GB",
        "10737418240; 10 GB",
        "1099511627776; 1 TB",
        "1125899906842624; 1.024 TB"
      })
  void formatsWithBinaryUnitsAndAtMostOneGermanDecimal(long bytes, String expected) {
    assertThat(ByteSizes.format(bytes)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {"1075; 1 KB", "1126; 1,1 KB", "157286; 153,6 KB"})
  void roundsToOneDecimalAndDropsATrailingZero(long bytes, String expected) {
    assertThat(ByteSizes.format(bytes)).isEqualTo(expected);
  }
}
