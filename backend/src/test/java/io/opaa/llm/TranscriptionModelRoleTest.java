package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.common.FeatureFlags;
import io.opaa.llm.TranscriptionClient.ProbeFailure;
import io.opaa.llm.TranscriptionClient.TranscriptionUnavailableException;
import io.opaa.llm.TranscriptionModelRole.TranscriptionRoleUnusableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The four states of the transcription role and what moves it between them. */
class TranscriptionModelRoleTest {

  private static final byte[] AUDIO = {1, 2, 3};

  private final TranscriptionClient client = mock(TranscriptionClient.class);

  private static TranscriptionProperties properties(boolean enabled, String baseUrl, String model) {
    return new TranscriptionProperties(
        enabled, baseUrl, model, "key", "de", 1024, Duration.ofSeconds(5));
  }

  private static FeatureFlags speechInput(boolean on) {
    return new FeatureFlags(
        new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "" + on));
  }

  private TranscriptionModelRole role(TranscriptionProperties properties, boolean feature) {
    return new TranscriptionModelRole(properties, client, speechInput(feature));
  }

  @Test
  void switchedOffTheRoleIsDisabledAndContactsNobody() {
    TranscriptionModelRole role = role(properties(false, "http://stt:8000/v1", "whisper"), true);

    assertThat(role.refresh().state()).isEqualTo(TranscriptionRoleState.DISABLED);
    assertThat(role.usable()).isFalse();
    verifyNoInteractions(client);
  }

  @Test
  void theFeatureFlagSwitchesTheRoleOffAsWell() {
    TranscriptionModelRole role = role(properties(true, "http://stt:8000/v1", "whisper"), false);

    assertThat(role.refresh().state()).isEqualTo(TranscriptionRoleState.DISABLED);
    verifyNoInteractions(client);
  }

  @Test
  void switchedOnWithoutEndpointIsUnconfigured() {
    TranscriptionModelRole role = role(properties(true, "", ""), true);

    assertThat(role.refresh().state()).isEqualTo(TranscriptionRoleState.UNCONFIGURED);
    verifyNoInteractions(client);
  }

  @Test
  void anAddressWithCredentialsIsRejectedAndNeverShown() {
    TranscriptionModelRole role =
        role(properties(true, "https://user:pw@stt.example/v1", "whisper"), true);

    TranscriptionRoleStatus status = role.refresh();

    assertThat(status.state()).isEqualTo(TranscriptionRoleState.UNCONFIGURED);
    assertThat(status.baseUrlRejected()).isTrue();
    assertThat(status.baseUrl()).isNull();
    verifyNoInteractions(client);
  }

  @Test
  void aSuccessfulProbeMakesTheRoleReady() {
    TranscriptionModelRole role = role(properties(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any())).thenReturn(null);

    assertThat(role.usable()).isFalse();
    assertThat(role.refresh().state()).isEqualTo(TranscriptionRoleState.READY);
    assertThat(role.usable()).isTrue();
  }

  @Test
  void aFailedProbeMakesTheRoleUnreachable() {
    TranscriptionModelRole role = role(properties(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any())).thenReturn(new ProbeFailure("request timed out", true));

    TranscriptionRoleStatus status = role.refresh();

    assertThat(status.state()).isEqualTo(TranscriptionRoleState.UNREACHABLE);
    assertThat(status.timedOut()).isTrue();
    assertThat(role.usable()).isFalse();
  }

  @Test
  void aRecordingIsNotSentWhileTheRoleIsNotReady() {
    TranscriptionModelRole role = role(properties(true, "http://stt:8000/v1", "whisper"), true);

    assertThatThrownBy(() -> role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM))
        .isInstanceOf(TranscriptionRoleUnusableException.class);
    verifyNoInteractions(client);
  }

  @Test
  void aFailedCallMovesTheRoleToUnreachable() {
    TranscriptionModelRole role = role(properties(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any())).thenReturn(null);
    role.refresh();
    when(client.transcribe(any(), any(), any()))
        .thenThrow(new TranscriptionUnavailableException("connection refused", false));

    assertThatThrownBy(() -> role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM))
        .isInstanceOf(TranscriptionUnavailableException.class);
    assertThat(role.currentStatus().state()).isEqualTo(TranscriptionRoleState.UNREACHABLE);
  }

  @Test
  void aSuccessfulCallReturnsTheText() {
    TranscriptionModelRole role = role(properties(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any())).thenReturn(null);
    role.refresh();
    when(client.transcribe(any(), any(), any())).thenReturn("Hallo");

    assertThat(role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM)).isEqualTo("Hallo");
    assertThat(role.usable()).isTrue();
  }
}
