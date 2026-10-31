package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.common.ServiceUnavailableException;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.ChatResponses;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@ExtendWith(MockitoExtension.class)
class AnswerStreamingTest {

  @Mock private ChatModel chatModel;
  @Mock private ActiveChatModelResolver activeChatModelResolver;

  private ChatMemory chatMemory;
  private ActiveAnswerStreams activeAnswerStreams;
  private AnswerGenerationService service;
  private final RecordingListener listener = new RecordingListener();

  @BeforeEach
  void setUp() {
    lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    lenient()
        .when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    chatMemory = MessageWindowChatMemory.builder().build();
    activeAnswerStreams = new ActiveAnswerStreams();
    activeAnswerStreams.start();
    service = new AnswerGenerationService(activeChatModelResolver, chatMemory, activeAnswerStreams);
  }

  @Test
  void theChunksReachTheListenerAndAddUpToTheResponseOfTheCallWithoutStreaming() {
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                chunk("Die Frist ", null, null),
                chunk("beträgt einen Monat.", "stop", null),
                chunk(null, null, new DefaultUsage(120, 30, 150))));

    ChatResponse response = stream("conv-1");

    assertThat(listener.events)
        .containsExactly("started", "delta:Die Frist ", "delta:beträgt einen Monat.");
    assertThat(ChatResponses.text(response)).isEqualTo("Die Frist beträgt einen Monat.");
    assertThat(ChatResponses.totalTokens(response)).isEqualTo(150);
    assertThat(ChatResponses.model(response)).isEqualTo("stub-model");
    assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
    assertThat(chatMemory.get("conv-1")).hasSize(2);
    assertThat(activeAnswerStreams.openStreams()).isZero();
    verify(chatModel, never()).call(any(Prompt.class));
  }

  @Test
  void theCompleteResponseLosesItsLeadingReasoningText() {
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(chunk("<think>hm</think>\n", null, null), chunk("Ja.", null, null)));

    ChatResponse response = stream("conv-reasoning");

    assertThat(listener.events).containsExactly("started", "delta:Ja.");
    assertThat(ChatResponses.text(response)).isEqualTo("Ja.");
  }

  @Test
  void aModelThatRefusesTheStreamIsAskedOnceMoreWithoutStreaming() {
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.error(new IllegalStateException("stream_options not supported")));
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort")))));

    ChatResponse response = stream("conv-fallback");

    assertThat(ChatResponses.text(response)).isEqualTo("Antwort");
    assertThat(listener.events).isEmpty();
    assertThat(chatMemory.get("conv-fallback")).hasSize(2);
  }

  @Test
  void anUnavailableModelIsNotAskedTwice() {
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.error(new TransientAiException("overloaded")));

    assertThatThrownBy(() -> stream("conv-down")).isInstanceOf(TransientAiException.class);

    verify(chatModel, never()).call(any(Prompt.class));
    assertThat(listener.events).isEmpty();
    assertThat(chatMemory.get("conv-down")).isEmpty();
  }

  @Test
  void aFailureAfterTheFirstChunkEndsTheTurnWithoutAnAnswer() {
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.concat(
                Flux.just(chunk("Teil", null, null)),
                Flux.error(new IllegalStateException("connection reset"))));

    assertThatThrownBy(() -> stream("conv-broken"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("connection reset");

    assertThat(listener.events).containsExactly("started", "delta:Teil");
    verify(chatModel, never()).call(any(Prompt.class));
    assertThat(chatMemory.get("conv-broken")).isEmpty();
  }

  @Test
  void aReaderThatWentAwayCancelsTheModelCall() {
    AtomicBoolean cancelled = new AtomicBoolean();
    Sinks.Many<ChatResponse> model = Sinks.many().unicast().onBackpressureBuffer();
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(model.asFlux().doOnCancel(() -> cancelled.set(true)));
    listener.failOnDelta = true;
    model.tryEmitNext(chunk("Erster Teil", null, null));

    assertThatThrownBy(() -> stream("conv-gone")).isInstanceOf(UncheckedIOException.class);

    assertThat(cancelled).isTrue();
    assertThat(chatMemory.get("conv-gone")).isEmpty();
    assertThat(activeAnswerStreams.openStreams()).isZero();
  }

  @Test
  void theShutdownEndsARunningStreamAtOnceWithItsNotice() throws Exception {
    AtomicBoolean cancelled = new AtomicBoolean();
    Sinks.Many<ChatResponse> model = Sinks.many().unicast().onBackpressureBuffer();
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(model.asFlux().doOnCancel(() -> cancelled.set(true)));
    model.tryEmitNext(chunk("Erster Teil", null, null));

    CompletableFuture<ChatResponse> running =
        CompletableFuture.supplyAsync(() -> stream("conv-stop"));
    while (activeAnswerStreams.openStreams() == 0 || listener.events.size() < 2) {
      Thread.onSpinWait();
    }
    activeAnswerStreams.stop();

    assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS))
        .cause()
        .isInstanceOf(ServiceUnavailableException.class)
        .extracting(failure -> ((ServiceUnavailableException) failure).getCode())
        .isEqualTo(ActiveAnswerStreams.SHUTTING_DOWN);
    assertThat(cancelled).isTrue();
    assertThat(chatMemory.get("conv-stop")).isEmpty();
  }

  @Test
  void noStreamStartsOnceTheInstanceStops() {
    activeAnswerStreams.stop();

    assertThatThrownBy(() -> stream("conv-late"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("neu startet");
    verify(chatModel, never()).stream(any(Prompt.class));
  }

  private ChatResponse stream(String conversationId) {
    return service.streamAnswer(
        "Wie lang ist die Frist?",
        List.of(),
        conversationId,
        List.of(),
        true,
        true,
        null,
        listener);
  }

  private static ChatResponse chunk(String text, String finishReason, DefaultUsage usage) {
    ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model("stub-model");
    if (usage != null) {
      metadata.usage(usage);
    }
    List<Generation> generations =
        text == null && finishReason == null
            ? List.of()
            : List.of(
                new Generation(
                    new AssistantMessage(text == null ? "" : text),
                    ChatGenerationMetadata.builder().finishReason(finishReason).build()));
    return new ChatResponse(generations, metadata.build());
  }

  private static final class RecordingListener implements AnswerStreamListener {

    private final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean failOnDelta;

    @Override
    public void started() {
      events.add("started");
    }

    @Override
    public void provisional(String text, boolean replace) throws IOException {
      if (failOnDelta) {
        throw new IOException("Broken pipe");
      }
      events.add((replace ? "reset:" : "delta:") + text);
    }

    @Override
    public void idle() {
      events.add("idle");
    }
  }
}
