package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QueryEmbeddingTimeTest {

  @Test
  void aMeasurementSumsWhatItsWorkAdds() {
    QueryEmbeddingTime.Measured<String> measured =
        QueryEmbeddingTime.measure(
            () -> {
              QueryEmbeddingTime.add(Duration.ofMillis(30).toNanos());
              QueryEmbeddingTime.add(Duration.ofMillis(12).toNanos());
              return "ok";
            });

    assertThat(measured.value()).isEqualTo("ok");
    assertThat(measured.embedding()).isEqualTo(Duration.ofMillis(42));
  }

  @Test
  void anAddOutsideAMeasurementGoesNowhere() {
    QueryEmbeddingTime.add(Duration.ofSeconds(5).toNanos());

    assertThat(QueryEmbeddingTime.measure(() -> 1).embedding()).isZero();
  }

  @Test
  void aNestedMeasurementAddsToTheOuterOne() {
    QueryEmbeddingTime.Measured<Duration> outer =
        QueryEmbeddingTime.measure(
            () -> {
              QueryEmbeddingTime.add(10);
              return QueryEmbeddingTime.measure(
                      () -> {
                        QueryEmbeddingTime.add(5);
                        return null;
                      })
                  .embedding();
            });

    assertThat(outer.value()).isEqualTo(Duration.ofNanos(5));
    assertThat(outer.embedding()).isEqualTo(Duration.ofNanos(15));
  }

  @Test
  void aFailedWorkUnbindsTheMeasurement() {
    assertThatThrownBy(
            () ->
                QueryEmbeddingTime.measure(
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);
    QueryEmbeddingTime.add(7);

    assertThat(QueryEmbeddingTime.measure(() -> 1).embedding()).isZero();
  }
}
