package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The binding rules of the catch-up interval of text recognition. */
class TextRecognitionPropertiesTest {

  private static TextRecognitionProperties withCatchUpInterval(Duration interval) {
    return new TextRecognitionProperties(true, 0, null, 0, 0, null, interval);
  }

  @Test
  void aMissingOrNonPositiveCatchUpIntervalFallsBackToFiveMinutes() {
    assertThat(withCatchUpInterval(null).catchUpInterval()).isEqualTo(Duration.ofMinutes(5));
    assertThat(withCatchUpInterval(Duration.ZERO).catchUpInterval())
        .isEqualTo(Duration.ofMinutes(5));
    assertThat(TextRecognitionProperties.disabled().catchUpInterval())
        .isEqualTo(Duration.ofMinutes(5));
  }

  /** Below the minimum every due time could find the lock of the previous pass still held. */
  @Test
  void aCatchUpIntervalBelowTheMinimumIsRejected() {
    assertThatThrownBy(() -> withCatchUpInterval(Duration.ofSeconds(19)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("opaa.ocr.catch-up-interval");
    assertThat(withCatchUpInterval(Duration.ofSeconds(20)).catchUpInterval())
        .isEqualTo(Duration.ofSeconds(20));
  }
}
