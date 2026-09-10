package io.opaa.query.answer;

import org.springframework.ai.chat.model.ChatResponse;

/**
 * Null-safe reading of a {@link ChatResponse}: a response, result, output or metadata a model left
 * empty is "no value", never an exception, so a degraded model answer cannot fail a turn that the
 * caller could still complete.
 */
public final class ChatResponses {

  private ChatResponses() {}

  /** The assistant text, or {@code null} when the response carries none. */
  public static String textOrNull(ChatResponse response) {
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null) {
      return null;
    }
    return response.getResult().getOutput().getText();
  }

  /** The assistant text, or the empty string when the response carries none. */
  public static String text(ChatResponse response) {
    String text = textOrNull(response);
    return text != null ? text : "";
  }

  /** The model that answered, or {@code "unknown"} when the response names none. */
  public static String model(ChatResponse response) {
    if (response != null
        && response.getMetadata() != null
        && response.getMetadata().getModel() != null) {
      return response.getMetadata().getModel();
    }
    return "unknown";
  }

  /** The turn's total token count, or {@code 0} when the response reports none. */
  public static int totalTokens(ChatResponse response) {
    if (response != null
        && response.getMetadata() != null
        && response.getMetadata().getUsage() != null) {
      return response.getMetadata().getUsage().getTotalTokens();
    }
    return 0;
  }
}
