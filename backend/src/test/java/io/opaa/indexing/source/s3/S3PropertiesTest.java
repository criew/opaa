package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link S3Properties}: defaults for absent values, the listing page size capped by S3 itself. */
class S3PropertiesTest {

  @Test
  void fillsDefaults() {
    S3Properties properties = new S3Properties(0, 0, null, null, null, 0, null);

    assertThat(properties.listPageSize()).isEqualTo(1000);
    assertThat(properties.maxObjectSizeBytes()).isEqualTo(50L * 1024 * 1024);
    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.maxRetries()).isEqualTo(5);
    assertThat(properties.retryBackoff()).isEqualTo(Duration.ofMillis(500));
    assertThat(properties.requestBudgetPerRun()).isZero();
    assertThat(properties.hasRequestBudget()).isFalse();
    assertThat(properties.tempDirectory()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir")));
    assertThat(new S3Properties(0, 0, null, 0, null, 0, Path.of("/srv/tmp")).maxRetries())
        .as("zero turns retries off, it is not 'absent'")
        .isZero();
    assertThat(new S3Properties(0, 0, null, 0, null, 0, Path.of("/srv/tmp")).tempDirectory())
        .isEqualTo(Path.of("/srv/tmp"));
    assertThat(S3Properties.defaults().requestBudgetPerRun())
        .isEqualTo(S3Properties.DEFAULT_REQUEST_BUDGET_PER_RUN);
    assertThat(S3Properties.defaults().hasRequestBudget()).isTrue();
  }

  @Test
  void aProbeCopyShortensTimeoutAndRetriesAndDropsTheBudget() {
    S3Properties run = new S3Properties(500, 0, null, null, null, 7, null);

    S3Properties probe = run.forProbe(java.time.Duration.ofSeconds(5), 1);

    assertThat(probe.listPageSize()).isEqualTo(500);
    assertThat(probe.requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
    assertThat(probe.maxRetries()).isEqualTo(1);
    assertThat(probe.requestBudgetPerRun()).isZero();
    assertThat(run.requestBudgetPerRun()).isEqualTo(7);
  }

  @Test
  void rejectsAPageSizeS3CannotServe() {
    assertThatThrownBy(() -> new S3Properties(1001, 0, null, null, null, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1000");
    assertThatThrownBy(() -> new S3Properties(-1, 0, null, null, null, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Properties(0, 0, null, -1, null, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Properties(0, 0, null, null, null, -1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
