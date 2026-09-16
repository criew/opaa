package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Docker-free guard for the opt-in that measures the multi-turn path against a production-grade
 * chat model (#1674). The failure this prevents is silent: a half-configured run that measures the
 * pinned model while its operator believes otherwise.
 */
class EvalChatModelExternalTest {

  private static final String BASE_URL = "opaa.eval.chatBaseUrl";
  private static final String MODEL = "opaa.eval.chatModel";

  @Test
  void withoutThePropertiesTheRunMeasuresThePinnedModel() {
    withProperties(
        null,
        null,
        () -> {
          assertThat(EvalChatModel.external()).isEmpty();
          assertThat(EvalChatModel.activeModelIdentifier()).isEqualTo(EvalChatModel.MODEL);
        });
  }

  @Test
  void halfAConfigurationFailsRatherThanMeasuringThePinnedModel() {
    withProperties(
        "https://api.anthropic.com/v1",
        null,
        () ->
            assertThatThrownBy(EvalChatModel::external)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belong together"));
    withProperties(
        null,
        "claude-haiku-4-5",
        () ->
            assertThatThrownBy(EvalChatModel::external)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belong together"));
  }

  /**
   * The key comes from the environment, never from a {@code -D} value. The test asserts the
   * behaviour reachable without setting an environment variable: both properties present and no key
   * is a failure, not a run against an unauthenticated endpoint.
   */
  @Test
  void aMissingApiKeyFailsAndNamesTheEnvironmentVariable() {
    Assumptions.assumeTrue(
        System.getenv("OPAA_EVAL_CHAT_API_KEY") == null,
        "Diese Maschine hat einen Schlüssel gesetzt - der Fehlerfall ist hier nicht herstellbar.");
    withProperties(
        "https://api.anthropic.com/v1",
        "claude-haiku-4-5",
        () ->
            assertThatThrownBy(EvalChatModel::external)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPAA_EVAL_CHAT_API_KEY")
                .hasMessageContaining("process list"));
  }

  private static void withProperties(String baseUrl, String model, Runnable assertions) {
    String previousBaseUrl = System.getProperty(BASE_URL);
    String previousModel = System.getProperty(MODEL);
    set(BASE_URL, baseUrl);
    set(MODEL, model);
    try {
      assertions.run();
    } finally {
      set(BASE_URL, previousBaseUrl);
      set(MODEL, previousModel);
    }
  }

  private static void set(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
