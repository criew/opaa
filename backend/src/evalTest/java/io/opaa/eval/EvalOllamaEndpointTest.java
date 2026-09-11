package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit test for the {@code opaa.eval.ollamaBaseUrl} system property resolution (issue #1076). */
class EvalOllamaEndpointTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY);
  }

  @Test
  void isNotExternalWhenPropertyUnset() {
    System.clearProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY);

    assertThat(EvalOllamaEndpoint.isExternal()).isFalse();
    assertThat(EvalOllamaEndpoint.externalBaseUrl()).isNull();
  }

  @Test
  void isNotExternalWhenPropertyBlank() {
    System.setProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY, "   ");

    assertThat(EvalOllamaEndpoint.isExternal()).isFalse();
    assertThat(EvalOllamaEndpoint.externalBaseUrl()).isNull();
  }

  @Test
  void isExternalAndTrimmedWhenPropertySet() {
    System.setProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY, "  http://localhost:11434  ");

    assertThat(EvalOllamaEndpoint.isExternal()).isTrue();
    assertThat(EvalOllamaEndpoint.externalBaseUrl()).isEqualTo("http://localhost:11434");
  }

  /**
   * Issue #1522: the marker a run writes into {@code ollamaImage} and the marker a baseline is
   * refused for are the same string — a baseline guard that reads a different prefix would stop
   * firing without any test noticing.
   */
  @Test
  void theExternalMarkerWrittenByARunIsTheOneTheBaselineGuardRecognizes() {
    System.setProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY, "http://localhost:11434");

    String recorded = EvalOllamaEndpoint.describeImageOrEndpoint("ollama/ollama:0.6.5");

    assertThat(recorded).isEqualTo("extern: http://localhost:11434");
    assertThat(EvalOllamaEndpoint.describesExternalEndpoint(recorded)).isTrue();
  }

  @Test
  void aPinnedContainerImageDoesNotDescribeAnExternalEndpoint() {
    assertThat(EvalOllamaEndpoint.describesExternalEndpoint("ollama/ollama:0.6.5")).isFalse();
    assertThat(EvalOllamaEndpoint.describesExternalEndpoint(null)).isFalse();
  }
}
