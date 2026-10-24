package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Docker-free guard for the opt-in that measures the multi-turn path against a production-grade
 * chat model. The failure this prevents is silent: a half-configured run that measures the pinned
 * model while its operator believes otherwise.
 */
class EvalChatModelExternalTest {

  private static final String BASE_URL = "opaa.eval.chatBaseUrl";
  private static final String MODEL = "opaa.eval.chatModel";
  private static final String TEMPERATURE = "opaa.eval.chatTemperature";

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
   * The key comes from the environment only and is optional: an endpoint without authentication,
   * such as an Ollama in the own network, is measured without one instead of with a dummy.
   */
  @Test
  void withoutAnApiKeyTheEndpointIsMeasuredUnauthenticated() {
    Assumptions.assumeTrue(
        System.getenv("OPAA_EVAL_CHAT_API_KEY") == null,
        "Diese Maschine hat einen Schlüssel gesetzt - der Fall ohne Schlüssel ist hier nicht"
            + " herstellbar.");
    withProperties(
        "http://ollama.intern:11434/v1",
        "gemma3:4b",
        () -> {
          EvalChatModel.External external = EvalChatModel.external().orElseThrow();
          assertThat(external.apiKey()).isNull();
          assertThat(external.model()).isEqualTo("gemma3:4b");
        });
  }

  /** A key in the address would reach log and report, so such an address is refused outright. */
  @Test
  void anAddressWithUserInformationOrAQueryIsRefused() {
    for (String address :
        List.of(
            "https://nutzer:geheim@llm.example/v1",
            "http://u:geheim@llm_gw/v1",
            "https://llm.example/v1?api-key=geheim",
            "https://llm.example/v1#geheim")) {
      withProperties(
          address,
          "modell",
          () ->
              assertThatThrownBy(EvalChatModel::external)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("OPAA_EVAL_CHAT_API_KEY")
                  .hasMessageNotContaining("geheim"));
    }
    String previous = System.getProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY);
    try {
      System.setProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY, "http://u:geheim@localhost:11434");
      assertThatThrownBy(EvalOllamaEndpoint::externalBaseUrl)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("geheim");
      // an address URI cannot parse is refused as well, and nothing of it travels as a cause
      System.setProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY, "http://u:ge heim@llm gw:11434");
      assertThatThrownBy(EvalOllamaEndpoint::externalBaseUrl)
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause()
          .hasMessageNotContaining("heim");
      System.setProperty(EvalOllamaEndpoint.BASE_URL_PROPERTY, "http://localhost:11434");
      assertThat(EvalOllamaEndpoint.externalBaseUrl()).isEqualTo("http://localhost:11434");
    } finally {
      set(EvalOllamaEndpoint.BASE_URL_PROPERTY, previous);
    }
  }

  /** A report names the endpoint, never a key carried in user information or a query. */
  @Test
  void theReportedEndpointCarriesNoUserInformationAndNoQuery() {
    assertThat(
            EvalChatModel.endpointWithoutSecrets(
                "https://nutzer:geheim@llm.example:8443/v1?api-key=geheim#x"))
        .isEqualTo("https://llm.example:8443/v1");
    assertThat(EvalChatModel.endpointWithoutSecrets("http://localhost:11434/v1"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(EvalChatModel.endpointWithoutSecrets("kein url geheim"))
        .isEqualTo("(Adresse nicht lesbar)");
    assertThat(EvalChatModel.endpointWithoutSecrets(null)).isNull();
  }

  /** A Befundlauf reproduces an installation's temperature; the pinned model never leaves 0. */
  @Test
  void aTemperatureIsReadForAnExternalModelAndRefusedForThePinnedOne() {
    String previous = System.getProperty(TEMPERATURE);
    try {
      System.clearProperty(TEMPERATURE);
      assertThat(EvalChatModel.requestedTemperature()).isEqualByComparingTo("0.00");

      System.setProperty(TEMPERATURE, "0.70");
      assertThat(EvalChatModel.requestedTemperature()).isEqualByComparingTo("0.70");
      withProperties(
          null,
          null,
          () ->
              assertThatThrownBy(EvalChatModel::refuseTemperatureWithoutExternalModel)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("external chat model only"));

      System.setProperty(TEMPERATURE, "warm");
      assertThatThrownBy(EvalChatModel::requestedTemperature)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("decimal number");
      System.setProperty(TEMPERATURE, "2.5");
      assertThatThrownBy(EvalChatModel::requestedTemperature)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 0 and 2");
    } finally {
      set(TEMPERATURE, previous);
    }
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
