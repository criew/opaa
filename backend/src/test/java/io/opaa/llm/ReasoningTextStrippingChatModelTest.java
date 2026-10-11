package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Every answer of the wrapped model reaches the caller without leading reasoning text, and with
 * everything else - metadata, usage, finish reason, tool calls - as the model returned it.
 */
class ReasoningTextStrippingChatModelTest {

  private final ChatModel delegate = mock(ChatModel.class);
  private final ChatModel model = new ReasoningTextStrippingChatModel(delegate);

  private ChatResponse answer(AssistantMessage message, String finishReason) {
    ChatResponse response =
        new ChatResponse(
            List.of(
                new Generation(
                    message, ChatGenerationMetadata.builder().finishReason(finishReason).build())),
            ChatResponseMetadata.builder()
                .model("gemma-4-26b-a4b-it")
                .usage(new DefaultUsage(256, 17, 528))
                .build());
    when(delegate.call(any(Prompt.class))).thenReturn(response);
    return response;
  }

  @Test
  void theLeadingReasoningBlockIsRemovedAndTheMetadataKept() {
    answer(new AssistantMessage("<thought>Denken</thought>Antwort [1]"), "stop");

    ChatResponse response = model.call(new Prompt("Frage"));

    assertThat(ChatResponses.text(response)).isEqualTo("Antwort [1]");
    assertThat(ChatResponses.model(response)).isEqualTo("gemma-4-26b-a4b-it");
    assertThat(ChatResponses.totalTokens(response)).isEqualTo(528);
    assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(17);
    assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
    assertThat(ChatResponses.completeness(response)).isEqualTo(AnswerCompleteness.COMPLETE);
  }

  @Test
  void anAnswerWithoutReasoningIsReturnedAsIs() {
    ChatResponse original = answer(new AssistantMessage("Antwort"), "stop");

    assertThat(model.call(new Prompt("Frage"))).isSameAs(original);
  }

  @Test
  void anUnclosedReasoningBlockCountsAsAnEmptyAnswer() {
    answer(new AssistantMessage("<think>Das Limit kam vor dem Ende"), "length");

    ChatResponse response = model.call(new Prompt("Frage"));

    assertThat(ChatResponses.text(response)).isEmpty();
    assertThat(ChatResponses.completeness(response)).isEqualTo(AnswerCompleteness.EMPTY);
  }

  @Test
  void toolCallsAndPropertiesSurviveTheCleanup() {
    AssistantMessage.ToolCall toolCall =
        new AssistantMessage.ToolCall("call-1", "function", "search", "{}");
    answer(
        AssistantMessage.builder()
            .content("<think>Ich suche.</think>")
            .toolCalls(List.of(toolCall))
            .properties(Map.of("role", "ASSISTANT"))
            .build(),
        "tool_calls");

    ChatResponse response = model.call(new Prompt("Frage"));

    AssistantMessage output = response.getResult().getOutput();
    assertThat(output.getText()).isEmpty();
    assertThat(output.getToolCalls()).containsExactly(toolCall);
    assertThat(output.getMetadata()).containsEntry("role", "ASSISTANT");
  }

  @Test
  void aChatClientOnTheWrappedModelReadsTheCleanedText() {
    when(delegate.getOptions()).thenReturn(ChatOptions.builder().build());
    answer(new AssistantMessage("<think>x</think>\nKosten Personalausweis"), "stop");

    String content = ChatClient.builder(model).build().prompt().user("Frage").call().content();

    assertThat(content).isEqualTo("Kosten Personalausweis");
  }
}
