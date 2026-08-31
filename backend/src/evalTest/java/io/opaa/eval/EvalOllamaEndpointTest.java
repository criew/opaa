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
}
