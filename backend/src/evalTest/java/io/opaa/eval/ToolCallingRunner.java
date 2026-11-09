package io.opaa.eval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Drives one case through a {@link ChatModel} by hand: every tool call the model makes is answered
 * by the harness from the case's fixed results, never executed, and the transcript records what the
 * model did. The loop stops at the first response without a tool call or after {@code callLimit +
 * EXTRA_ROUNDS} model calls, so a model that keeps calling costs a bounded number of requests.
 *
 * <p>Call accounting: every tool call counts towards the case's limit, whatever its name. The
 * result of the call that reaches the limit carries the mock tool's limit notice; a call beyond the
 * limit receives the notice alone.
 */
final class ToolCallingRunner {

  /** Model calls allowed beyond the case's call limit before the run is cut off. */
  static final int EXTRA_ROUNDS = 3;

  private static final int MAX_ERROR_LENGTH = 300;

  private final ToolCallingMockTool tool;
  private final boolean streaming;
  private final Duration streamTimeout;

  ToolCallingRunner(ToolCallingMockTool tool, boolean streaming, Duration streamTimeout) {
    this.tool = tool;
    this.streaming = streaming;
    this.streamTimeout = streamTimeout;
  }

  Transcript run(ToolCallingCase c, ChatModel model) {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(tool.systemPrompt(c.callLimit())));
    messages.add(new UserMessage(c.userMessage()));
    ToolCallingChatOptions options = optionsWithTool(model);
    List<ToolCall> calls = new ArrayList<>();
    List<Long> modelCallMillis = new ArrayList<>();
    int validCalls = 0;
    int injectionDeliveredAt = -1;
    try {
      for (int round = 0; round < c.callLimit() + EXTRA_ROUNDS; round++) {
        long start = System.nanoTime();
        AssistantMessage output = call(model, new Prompt(List.copyOf(messages), options));
        modelCallMillis.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
        if (output == null || !output.hasToolCalls()) {
          String text = output == null ? null : output.getText();
          return new Transcript(
              calls, modelCallMillis, text == null ? "" : text, false, null, injectionDeliveredAt);
        }
        messages.add(output);
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
          int number = calls.size() + 1;
          ToolCallingMockTool.ArgumentCheck check = ToolCallingMockTool.check(toolCall.arguments());
          boolean known = tool.name().equals(toolCall.name());
          String result;
          if (number > c.callLimit()) {
            result = tool.limitReachedNotice();
          } else if (!known) {
            result = tool.unknownToolResult();
          } else if (!check.valid()) {
            result = tool.invalidArgumentsResult();
          } else {
            if (c.injection() != null && c.injection().resultIndex() == validCalls) {
              injectionDeliveredAt = calls.size();
            }
            result = c.toolResult(validCalls);
            validCalls++;
          }
          if (number == c.callLimit()) {
            result = result + "\n\n" + tool.limitReachedNotice();
          }
          calls.add(
              new ToolCall(
                  round,
                  toolCall.name(),
                  toolCall.arguments(),
                  known,
                  check,
                  number > c.callLimit()));
          responses.add(
              new ToolResponseMessage.ToolResponse(
                  Objects.requireNonNullElse(toolCall.id(), "call-" + number),
                  toolCall.name(),
                  result));
        }
        messages.add(ToolResponseMessage.builder().responses(responses).build());
      }
      return new Transcript(calls, modelCallMillis, null, true, null, injectionDeliveredAt);
    } catch (RuntimeException e) {
      return new Transcript(calls, modelCallMillis, null, false, describe(e), injectionDeliveredAt);
    }
  }

  /**
   * The model's own default options plus the mock tool: a provider model accepts only its own
   * options type per call, so the tool goes onto a copy of those rather than onto generic ones.
   */
  private ToolCallingChatOptions optionsWithTool(ChatModel model) {
    List<ToolCallback> callbacks = List.of(tool.callback());
    if (model.getOptions() instanceof ToolCallingChatOptions defaults) {
      return defaults.mutate().toolCallbacks(callbacks).build();
    }
    return ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
  }

  private AssistantMessage call(ChatModel model, Prompt prompt) {
    if (!streaming) {
      ChatResponse response = model.call(prompt);
      return response == null || response.getResult() == null
          ? null
          : response.getResult().getOutput();
    }
    List<ChatResponse> chunks = model.stream(prompt).collectList().block(streamTimeout);
    return aggregate(chunks == null ? List.of() : chunks);
  }

  /**
   * One message from the chunks of a streamed answer: the texts concatenated, the tool calls of all
   * chunks by id, a later chunk's name and arguments replacing an earlier one's for the same id.
   */
  static AssistantMessage aggregate(List<ChatResponse> chunks) {
    StringBuilder text = new StringBuilder();
    Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
    for (ChatResponse chunk : chunks) {
      if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
        continue;
      }
      AssistantMessage output = chunk.getResult().getOutput();
      if (output.getText() != null) {
        text.append(output.getText());
      }
      for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
        String key = toolCall.id() == null ? "index-" + toolCalls.size() : toolCall.id();
        toolCalls.put(key, toolCall);
      }
    }
    return AssistantMessage.builder()
        .content(text.toString())
        .toolCalls(List.copyOf(toolCalls.values()))
        .build();
  }

  private static String describe(RuntimeException e) {
    String message = e.getClass().getSimpleName() + ": " + e.getMessage();
    return message.length() > MAX_ERROR_LENGTH
        ? message.substring(0, MAX_ERROR_LENGTH) + "…"
        : message;
  }

  /**
   * One tool call the model made.
   *
   * @param round the model call (from 0) whose response carried it
   * @param known whether it named the mock tool
   * @param beyondLimit whether it came after the case's call limit was used up
   */
  record ToolCall(
      int round,
      String name,
      String arguments,
      boolean known,
      ToolCallingMockTool.ArgumentCheck check,
      boolean beyondLimit) {}

  /**
   * What the model did in one run of one case.
   *
   * @param finalAnswer the text of the first response without a tool call; {@code null} when the
   *     run was cut off or failed
   * @param cutOff whether the run ended at the round cap while the model still called tools
   * @param error the failure of a model call, without credentials; {@code null} when none occurred
   * @param injectionDeliveredAt index into {@code toolCalls} of the call whose result carried the
   *     case's embedded instruction, {@code -1} when it was never delivered
   */
  record Transcript(
      List<ToolCall> toolCalls,
      List<Long> modelCallMillis,
      String finalAnswer,
      boolean cutOff,
      String error,
      int injectionDeliveredAt) {

    Transcript {
      toolCalls = List.copyOf(toolCalls);
      modelCallMillis = List.copyOf(modelCallMillis);
    }

    /** The calls that named the mock tool, in order. */
    List<ToolCall> knownCalls() {
      return toolCalls.stream().filter(ToolCall::known).toList();
    }
  }
}
