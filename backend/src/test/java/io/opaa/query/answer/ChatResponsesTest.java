package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * The one reading of a model answer used by all three model calls of a turn: absent parts of a
 * response are "no value", an absent response is a failure.
 */
class ChatResponsesTest {

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  @Test
  void aResponseWithoutAResultCarriesNoText() {
    ChatResponse empty = new ChatResponse(List.of());

    assertThat(ChatResponses.textOrNull(empty)).isNull();
    assertThat(ChatResponses.text(empty)).isEmpty();
  }

  @Test
  void anAnswerTextIsReturnedUnchanged() {
    assertThat(ChatResponses.textOrNull(response("Die Antwort"))).isEqualTo("Die Antwort");
    assertThat(ChatResponses.text(response("Die Antwort"))).isEqualTo("Die Antwort");
  }

  /**
   * A response built without metadata carries an empty default metadata object rather than none:
   * its model name is passed through as-is, and its absent usage counts as no tokens.
   */
  @Test
  void aResponseWithDefaultMetadataReportsWhatThatMetadataCarries() {
    ChatResponse withDefaultMetadata = response("Antwort");

    assertThat(ChatResponses.model(withDefaultMetadata)).isEmpty();
    assertThat(ChatResponses.totalTokens(withDefaultMetadata)).isZero();
  }

  /** Metadata a model implementation left out entirely is "no value", not a failure. */
  @Test
  void missingMetadataReportsTheUnknownModelAndNoTokens() {
    ChatResponse withoutMetadata = mock(ChatResponse.class);
    when(withoutMetadata.getMetadata()).thenReturn(null);

    assertThat(ChatResponses.model(withoutMetadata)).isEqualTo("unknown");
    assertThat(ChatResponses.totalTokens(withoutMetadata)).isZero();
  }

  @Test
  void modelAndTokenCountAreReadFromTheMetadata() {
    ChatResponse withMetadata =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("Antwort"))),
            ChatResponseMetadata.builder()
                .model("gpt-4o")
                .usage(new DefaultUsage(100, 200))
                .build());

    assertThat(ChatResponses.model(withMetadata)).isEqualTo("gpt-4o");
    assertThat(ChatResponses.totalTokens(withMetadata)).isEqualTo(300);
  }

  /**
   * A missing response is a broken model call, not an empty answer: every accessor rejects it, so
   * the turn fails through the caller's error path instead of being recorded as a successful, empty
   * answer.
   */
  @Test
  void aMissingResponseIsRejectedByEveryAccessor() {
    assertThatThrownBy(() -> ChatResponses.textOrNull(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("no response");
    assertThatThrownBy(() -> ChatResponses.text(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ChatResponses.model(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ChatResponses.totalTokens(null))
        .isInstanceOf(NullPointerException.class);
  }
}
