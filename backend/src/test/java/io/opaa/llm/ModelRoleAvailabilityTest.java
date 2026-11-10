package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.common.FeatureFlags;
import io.opaa.health.EmbeddingsHealthIndicator;
import io.opaa.health.TranscriptionHealthIndicator;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code opaa.model.role.available} mirrors the state the roles keep for their health groups, never
 * calls a model itself, and has no series for a role that is switched off or not configured.
 */
class ModelRoleAvailabilityTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private static OptionalRoleStatus status(OptionalRoleState state) {
    return new OptionalRoleStatus(state, "http://model:8000/v1", "model", null);
  }

  private ModelRoleAvailability availability(
      boolean chatConfigured,
      boolean chatAvailable,
      boolean embeddingConfigured,
      Supplier<OptionalRoleStatus> rerank,
      Supplier<OptionalRoleStatus> transcription,
      Supplier<OptionalRoleStatus> textRecognition,
      TextRecognitionConnectionType textRecognitionType) {
    return new ModelRoleAvailability(
        () -> chatConfigured ? "chat-model" : null,
        model -> chatAvailable,
        () -> embeddingConfigured ? "embedding-model" : null,
        model -> true,
        rerank,
        transcription,
        textRecognition,
        () -> textRecognitionType,
        meterRegistry);
  }

  private Map<String, Double> series() {
    return meterRegistry.find(ModelRoleAvailability.METRIC).gauges().stream()
        .collect(Collectors.toMap(gauge -> gauge.getId().getTag("role"), Gauge::value));
  }

  @Test
  void everyConfiguredRoleHasASeriesWithItsState() {
    ModelRoleAvailability availability =
        availability(
            true,
            false,
            true,
            () -> status(OptionalRoleState.READY),
            () -> status(OptionalRoleState.UNREACHABLE),
            () -> status(OptionalRoleState.READY),
            TextRecognitionConnectionType.OPENAI_VISION);

    availability.refresh();

    assertThat(series())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "chat", 0.0,
                "embedding", 1.0,
                "rerank", 1.0,
                "transcription", 0.0,
                "vision", 1.0));
  }

  @Test
  void aRoleSwitchedOffOrNotConfiguredHasNoSeries() {
    ModelRoleAvailability availability =
        availability(
            false,
            true,
            false,
            OptionalRoleStatus::disabled,
            () -> status(OptionalRoleState.UNCONFIGURED),
            () -> status(OptionalRoleState.UNREACHABLE),
            TextRecognitionConnectionType.DOCLING_SERVE);

    availability.refresh();

    assertThat(series()).containsExactlyEntriesOf(Map.of("ocr", 0.0));
  }

  @Test
  void aRoleThatIsSwitchedOffLaterLosesItsSeries() {
    AtomicReference<OptionalRoleStatus> rerank =
        new AtomicReference<>(status(OptionalRoleState.READY));
    ModelRoleAvailability availability =
        availability(
            false,
            true,
            false,
            rerank::get,
            OptionalRoleStatus::disabled,
            OptionalRoleStatus::disabled,
            TextRecognitionConnectionType.DOCLING_SERVE);
    availability.refresh();
    assertThat(series()).containsOnlyKeys("rerank");

    rerank.set(OptionalRoleStatus.disabled());
    availability.refresh();

    assertThat(series()).isEmpty();
  }

  /** The transcription role as the health group sees it, and nothing calls the endpoint. */
  @Test
  void theGaugeMirrorsTheHealthGroupWithoutACallOfItsOwn() {
    TranscriptionClient client = mock(TranscriptionClient.class);
    when(client.probe(any(), any()))
        .thenReturn(new TranscriptionClient.ProbeFailure("connection refused", false));
    TranscriptionModelRole role =
        new TranscriptionModelRole(
            new TranscriptionProperties(1024, Duration.ofSeconds(5), 2),
            () -> new TranscriptionEndpoint(true, "http://stt:8000/v1", "whisper", "key", "de"),
            client,
            new FeatureFlags(
                new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "true")));
    TranscriptionHealthIndicator health = new TranscriptionHealthIndicator(role);
    role.refresh();
    TranscriptionClient untouched = mock(TranscriptionClient.class);
    TranscriptionModelRole observed =
        new TranscriptionModelRole(
            new TranscriptionProperties(1024, Duration.ofSeconds(5), 2),
            role::endpoint,
            untouched,
            new FeatureFlags(
                new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "true")),
            role.observations());
    ModelRoleAvailability availability =
        availability(
            false,
            true,
            false,
            OptionalRoleStatus::disabled,
            observed::currentStatus,
            OptionalRoleStatus::disabled,
            TextRecognitionConnectionType.DOCLING_SERVE);

    availability.refresh();

    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(series()).containsExactlyEntriesOf(Map.of("transcription", 0.0));
    verifyNoInteractions(untouched);
  }

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

  /**
   * The embedding health probe embeds through the observed model, so a refused probe makes the role
   * unavailable exactly as the health group reports it, with no call of the gauge's own.
   */
  @Test
  void theEmbeddingRoleMirrorsItsHealthProbe() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });
    server.start();
    try {
      ModelCallOutcomes outcomes = new ModelCallOutcomes();
      ObservationRegistry observations = ObservationRegistry.create();
      observations.observationConfig().observationHandler(outcomes);
      @SuppressWarnings("unchecked")
      ObjectProvider<ObservationRegistry> provider = mock(ObjectProvider.class);
      when(provider.getIfUnique(any())).thenReturn(observations);
      EmbeddingModel model =
          new OpenAiEmbeddingClientFactory(Duration.ofSeconds(5), provider)
              .create(
                  new EmbeddingEndpoint(
                      "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                      "bge-m3",
                      "key",
                      EmbeddingEndpoint.DEFAULT_MAX_INPUT_TOKENS));
      EmbeddingsHealthIndicator health =
          new EmbeddingsHealthIndicator(
              model, mock(EmbeddingModelService.class), Clock.systemUTC());
      ModelRoleAvailability availability =
          new ModelRoleAvailability(
              () -> null,
              outcomes::chatAvailable,
              () -> "bge-m3",
              outcomes::embeddingAvailable,
              OptionalRoleStatus::disabled,
              OptionalRoleStatus::disabled,
              OptionalRoleStatus::disabled,
              () -> TextRecognitionConnectionType.DOCLING_SERVE,
              meterRegistry);

      assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
      availability.refresh();

      assertThat(series()).containsExactlyEntriesOf(Map.of("embedding", 0.0));
    } finally {
      server.stop(0);
    }
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
