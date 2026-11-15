package io.opaa.llm.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import io.opaa.common.FeatureFlags;
import io.opaa.llm.ModelRoleObservations;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import io.opaa.llm.ProcessLocalModelRoleObservations;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The state of the live upstream on top of the batch switch and the speech-input feature. */
class RealtimeTranscriptionRoleTest {

  private static final RealtimeTranscriptionEndpoint LIVE =
      new RealtimeTranscriptionEndpoint(
          RealtimeTranscriptionProtocol.VLLM, "ws://stt:8000/v1/realtime", "whisper", "", 1);

  private final RealtimeTranscriptionClient client = mock(RealtimeTranscriptionClient.class);
  private final ModelRoleObservations observations =
      new ProcessLocalModelRoleObservations(Clock.systemUTC());

  private RealtimeTranscriptionRole role(TranscriptionEndpoint endpoint, boolean feature) {
    TranscriptionProperties properties =
        new TranscriptionProperties(1024, Duration.ofSeconds(5), 2);
    FeatureFlags flags =
        new FeatureFlags(
            new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "" + feature));
    return new RealtimeTranscriptionRole(
        new TranscriptionModelRole(
            properties, () -> endpoint, mock(TranscriptionClient.class), flags),
        () -> endpoint,
        client,
        mock(RealtimeTranscriptionSlots.class),
        observations,
        properties);
  }

  private static TranscriptionEndpoint endpoint(
      boolean enabled, RealtimeTranscriptionEndpoint live) {
    return new TranscriptionEndpoint(enabled, "http://stt:8000/v1", "whisper", "", "de", "", live);
  }

  @Test
  void theBatchSwitchTheFeatureAndAMissingUpstreamLeaveLiveDisabledAndContactNobody() {
    assertThat(role(endpoint(false, LIVE), true).refresh().state())
        .isEqualTo(OptionalRoleState.DISABLED);
    assertThat(role(endpoint(true, LIVE), false).refresh().state())
        .isEqualTo(OptionalRoleState.DISABLED);
    assertThat(role(endpoint(true, RealtimeTranscriptionEndpoint.NONE), true).refresh().state())
        .isEqualTo(OptionalRoleState.DISABLED);
    verifyNoInteractions(client);
  }

  @Test
  void aLiveAddressWithCredentialsIsRejectedWithoutBeingProbed() {
    RealtimeTranscriptionEndpoint live =
        new RealtimeTranscriptionEndpoint(
            RealtimeTranscriptionProtocol.VLLM, "ws://u:p@stt:8000/v1/realtime", "whisper", "", 1);

    OptionalRoleStatus status = role(endpoint(true, live), true).refresh();

    assertThat(status.state()).isEqualTo(OptionalRoleState.UNCONFIGURED);
    assertThat(status.baseUrlRejected()).isTrue();
    assertThat(status.baseUrl()).isNull();
    verifyNoInteractions(client);
  }

  @Test
  void aProbeIsRecordedForAddressAndDialect() {
    RealtimeTranscriptionRole role = role(endpoint(true, LIVE), true);
    assertThat(role.currentStatus().state()).isEqualTo(OptionalRoleState.UNREACHABLE);

    when(client.probe(any(), any())).thenReturn(null);

    assertThat(role.refresh().state()).isEqualTo(OptionalRoleState.READY);
    assertThat(role.currentStatus().state()).isEqualTo(OptionalRoleState.READY);
    assertThat(observations.current(ModelRoleObservations.TRANSCRIPTION_REALTIME))
        .hasValueSatisfying(known -> assertThat(known.isFor(LIVE.url(), "VLLM whisper")).isTrue());
  }

  @Test
  void aFailedProbeKeepsItsDiagnosticAndTimeout() {
    RealtimeTranscriptionRole role = role(endpoint(true, LIVE), true);
    when(client.probe(any(), any()))
        .thenReturn(new RealtimeTranscriptionClient.ProbeFailure("timed out", true));

    OptionalRoleStatus status = role.refresh();

    assertThat(status.state()).isEqualTo(OptionalRoleState.UNREACHABLE);
    assertThat(status.timedOut()).isTrue();
    assertThat(status.diagnostic()).isEqualTo("timed out");
    assertThat(role.realtimeStatusFor(endpoint(false, LIVE)).state())
        .isEqualTo(OptionalRoleState.DISABLED);
  }
}
