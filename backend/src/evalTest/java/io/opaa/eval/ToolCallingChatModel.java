package io.opaa.eval;

import io.opaa.llm.ReasoningTextStrippingChatModel;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The chat model of a tool-calling measurement, built like the production client of an active model
 * entry ({@code ActiveChatModelResolver}): the one OpenAI-compatible path with address, key, model
 * identifier, length limit and temperature as options, wrapped in {@link
 * ReasoningTextStrippingChatModel}. Built directly rather than from an {@code llm_models} row
 * because the measurement needs no database.
 */
final class ToolCallingChatModel {

  private ToolCallingChatModel() {}

  /**
   * @param apiKey {@code null} for an endpoint without authentication
   * @param temperature {@code null} to send none, as for a model entry without a temperature
   */
  static ChatModel openAiCompatible(
      String baseUrl,
      String model,
      String apiKey,
      BigDecimal temperature,
      int maxTokens,
      Duration requestTimeout) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey == null ? "" : apiKey)
            .model(model)
            .maxTokens(maxTokens)
            .timeout(requestTimeout);
    if (temperature != null) {
      options.temperature(temperature.doubleValue());
    }
    return new ReasoningTextStrippingChatModel(
        OpenAiChatModel.builder().options(options.build()).build());
  }
}
