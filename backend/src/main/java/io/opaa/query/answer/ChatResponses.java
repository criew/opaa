package io.opaa.query.answer;

import java.util.Objects;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Reading a {@link ChatResponse} the way the three model calls of a turn read it: an absent result,
 * output, text or metadata is "no value", so a partial model answer does not fail a turn the caller
 * could still complete. A missing response itself is not tolerated - it is a broken model call, and
 * the turn fails on it.
 */
public final class ChatResponses {

  private ChatResponses() {}

  /** The assistant text, or {@code null} when the response carries none. */
  public static String textOrNull(ChatResponse response) {
    requireResponse(response);
    if (response.getResult() == null || response.getResult().getOutput() == null) {
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
    requireResponse(response);
    if (response.getMetadata() != null && response.getMetadata().getModel() != null) {
      return response.getMetadata().getModel();
    }
    return "unknown";
  }

  /** The turn's total token count, or {@code 0} when the response reports none. */
  public static int totalTokens(ChatResponse response) {
    requireResponse(response);
    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
      return response.getMetadata().getUsage().getTotalTokens();
    }
    return 0;
  }

  private static void requireResponse(ChatResponse response) {
    Objects.requireNonNull(response, "chat model returned no response");
  }
}
