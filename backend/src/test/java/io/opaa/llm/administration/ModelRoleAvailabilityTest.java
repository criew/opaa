package io.opaa.llm.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.common.FeatureFlags;
import io.opaa.health.TranscriptionHealthIndicator;
import io.opaa.llm.ModelRoleObservations;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import io.opaa.llm.ProcessLocalModelRoleObservations;
import io.opaa.llm.transcription.TranscriptionClient;
import io.opaa.llm.transcription.TranscriptionEndpoint;
import io.opaa.llm.transcription.TranscriptionModelRole;
import io.opaa.llm.transcription.TranscriptionProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
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
    ModelRoleObservations shared = new ProcessLocalModelRoleObservations(Clock.systemUTC());
    Supplier<TranscriptionEndpoint> endpoint =
        () -> new TranscriptionEndpoint(true, "http://stt:8000/v1", "whisper", "key", "de");
    TranscriptionModelRole role =
        new TranscriptionModelRole(
            new TranscriptionProperties(1024, Duration.ofSeconds(5), 2),
            endpoint,
            client,
            new FeatureFlags(
                new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "true")),
            shared);
    TranscriptionHealthIndicator health = new TranscriptionHealthIndicator(role);
    role.refresh();
    TranscriptionClient untouched = mock(TranscriptionClient.class);
    TranscriptionModelRole observed =
        new TranscriptionModelRole(
            new TranscriptionProperties(1024, Duration.ofSeconds(5), 2),
            endpoint,
            untouched,
            new FeatureFlags(
                new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "true")),
            shared);
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
}
