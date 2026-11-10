package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;

/** The outcome of the last real call per chat and embedding model, read by the role gauge. */
class ModelCallOutcomesTest {

  @Test
  void theLastRealCallOfTheRolesOwnModelDecidesItsAvailability() {
    ModelCallOutcomes outcomes = new ModelCallOutcomes();
    ChatModelObservationContext failedChat = chatCall("gpt-role");
    failedChat.setError(new java.net.ConnectException("refused"));

    assertThat(outcomes.supportsContext(failedChat)).isTrue();
    outcomes.onStop(failedChat);
    outcomes.onStop(embeddingCall("bge-m3"));

    assertThat(outcomes.chatAvailable("gpt-role")).isFalse();
    assertThat(outcomes.embeddingAvailable("bge-m3")).isTrue();
    outcomes.onStop(chatCall("gpt-role"));
    assertThat(outcomes.chatAvailable("gpt-role")).isTrue();
  }

  @Test
  void aCallOfAnotherModelNeverSpeaksForTheRolesModel() {
    ModelCallOutcomes outcomes = new ModelCallOutcomes();
    ChatModelObservationContext failedTitle = chatCall("small-title-model");
    failedTitle.setError(new java.net.ConnectException("refused"));

    outcomes.onStop(failedTitle);

    assertThat(outcomes.chatAvailable("gpt-role")).isTrue();
    assertThat(outcomes.chatAvailable("small-title-model")).isFalse();
  }

  @Test
  void aChangeOfTheModelDropsWhatWasHeld() {
    ModelCallOutcomes outcomes = new ModelCallOutcomes();
    ChatModelObservationContext failedChat = chatCall("gpt-role");
    failedChat.setError(new java.net.ConnectException("refused"));
    outcomes.onStop(failedChat);
    assertThat(outcomes.chatAvailable("gpt-role")).isFalse();

    assertThat(outcomes.chatAvailable("gpt-other")).isTrue();

    assertThat(outcomes.chatAvailable("gpt-role")).isTrue();
  }

  @Test
  void aLostOrRebuiltBusConnectionKeepsWhatWasHeld() {
    ModelCallOutcomes outcomes = new ModelCallOutcomes();
    ChatModelObservationContext failedChat = chatCall("gpt-role");
    failedChat.setError(new java.net.ConnectException("refused"));
    outcomes.onStop(failedChat);
    EmbeddingModelObservationContext failedEmbedding = embeddingCall("bge-m3");
    failedEmbedding.setError(new java.net.ConnectException("refused"));
    outcomes.onStop(failedEmbedding);

    assertThat(outcomes).isNotInstanceOf(io.opaa.instance.InvalidationSubscriber.class);
    assertThat(outcomes.chatAvailable("gpt-role")).isFalse();
    assertThat(outcomes.embeddingAvailable("bge-m3")).isFalse();
    assertThat(outcomes.chatAvailable("gpt-role")).isFalse();
    assertThat(outcomes.embeddingAvailable("bge-m3")).isFalse();
  }

  private static ChatModelObservationContext chatCall(String model) {
    return ChatModelObservationContext.builder()
        .prompt(new Prompt("Hallo", ChatOptions.builder().model(model).build()))
        .provider("openai")
        .build();
  }

  private static EmbeddingModelObservationContext embeddingCall(String model) {
    return EmbeddingModelObservationContext.builder()
        .embeddingRequest(
            new EmbeddingRequest(List.of("Text"), EmbeddingOptions.builder().model(model).build()))
        .provider("openai")
        .build();
  }

  @Test
  void aCallFailureCountsAsUnavailableOnlyWhenTheModelWasNotReached() {
    assertThat(ModelCallOutcomes.unavailable(new java.net.ConnectException("refused"))).isTrue();
    assertThat(
            ModelCallOutcomes.unavailable(
                new org.springframework.ai.retry.TransientAiException("HTTP 503")))
        .isTrue();
    assertThat(
            ModelCallOutcomes.unavailable(
                new org.springframework.ai.retry.NonTransientAiException("HTTP 400")))
        .isFalse();
    assertThat(ModelCallOutcomes.unavailable(new IllegalStateException("unreadable"))).isFalse();
  }
}
