package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link S3Properties}: defaults for absent values, the listing page size capped by S3 itself. */
class S3PropertiesTest {

  @Test
  void fillsDefaults() {
    S3Properties properties = new S3Properties(0, 0, null, 0, null, 0);

    assertThat(properties.listPageSize()).isEqualTo(1000);
    assertThat(properties.maxObjectSizeBytes()).isEqualTo(50L * 1024 * 1024);
    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.maxRetries()).isEqualTo(5);
    assertThat(properties.retryBackoff()).isEqualTo(Duration.ofMillis(500));
    assertThat(properties.requestBudgetPerRun()).isZero();
    assertThat(properties.hasRequestBudget()).isFalse();
    assertThat(S3Properties.defaults().requestBudgetPerRun())
        .isEqualTo(S3Properties.DEFAULT_REQUEST_BUDGET_PER_RUN);
    assertThat(S3Properties.defaults().hasRequestBudget()).isTrue();
  }

  @Test
  void rejectsAPageSizeS3CannotServe() {
    assertThatThrownBy(() -> new S3Properties(1001, 0, null, 0, null, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1000");
    assertThatThrownBy(() -> new S3Properties(-1, 0, null, 0, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Properties(0, 0, null, -1, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Properties(0, 0, null, 0, null, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
