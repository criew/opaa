package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.common.FeatureFlags;
import io.opaa.llm.TranscriptionClient.ProbeFailure;
import io.opaa.llm.TranscriptionClient.TranscriptionUnavailableException;
import io.opaa.llm.TranscriptionModelRole.TranscriptionRoleBusyException;
import io.opaa.llm.TranscriptionModelRole.TranscriptionRoleUnusableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The four states of the transcription role and what moves it between them. */
class TranscriptionModelRoleTest {

  private static final byte[] AUDIO = {1, 2, 3};

  private final TranscriptionClient client = mock(TranscriptionClient.class);

  private static TranscriptionEndpoint endpoint(boolean enabled, String baseUrl, String model) {
    return new TranscriptionEndpoint(enabled, baseUrl, model, "key", "de");
  }

  private static FeatureFlags speechInput(boolean on) {
    return new FeatureFlags(
        new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "" + on));
  }

  private TranscriptionModelRole role(TranscriptionEndpoint endpoint, boolean feature) {
    return new TranscriptionModelRole(
        new TranscriptionProperties(1024, Duration.ofSeconds(5), 2),
        () -> endpoint,
        client,
        speechInput(feature));
  }

  @Test
  void switchedOffTheRoleIsDisabledAndContactsNobody() {
    TranscriptionModelRole role = role(endpoint(false, "http://stt:8000/v1", "whisper"), true);

    assertThat(role.refresh().state()).isEqualTo(OptionalRoleState.DISABLED);
    assertThat(role.usable()).isFalse();
    verifyNoInteractions(client);
  }

  @Test
  void theFeatureFlagSwitchesTheRoleOffAsWell() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), false);

    assertThat(role.refresh().state()).isEqualTo(OptionalRoleState.DISABLED);
    verifyNoInteractions(client);
  }

  @Test
  void switchedOnWithoutEndpointIsUnconfigured() {
    TranscriptionModelRole role = role(endpoint(true, "", ""), true);

    assertThat(role.refresh().state()).isEqualTo(OptionalRoleState.UNCONFIGURED);
    verifyNoInteractions(client);
  }

  @Test
  void anAddressWithCredentialsIsRejectedAndNeverShown() {
    TranscriptionModelRole role =
        role(endpoint(true, "https://user:pw@stt.example/v1", "whisper"), true);

    OptionalRoleStatus status = role.refresh();

    assertThat(status.state()).isEqualTo(OptionalRoleState.UNCONFIGURED);
    assertThat(status.baseUrlRejected()).isTrue();
    assertThat(status.baseUrl()).isNull();
    verifyNoInteractions(client);
  }

  @Test
  void aSuccessfulProbeMakesTheRoleReady() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any(), any())).thenReturn(null);

    assertThat(role.usable()).isFalse();
    assertThat(role.refresh().state()).isEqualTo(OptionalRoleState.READY);
    assertThat(role.usable()).isTrue();
  }

  @Test
  void aFailedProbeMakesTheRoleUnreachable() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any(), any())).thenReturn(new ProbeFailure("request timed out", true));

    OptionalRoleStatus status = role.refresh();

    assertThat(status.state()).isEqualTo(OptionalRoleState.UNREACHABLE);
    assertThat(status.timedOut()).isTrue();
    assertThat(role.usable()).isFalse();
  }

  @Test
  void aRecordingIsNotSentWhileTheRoleIsNotReady() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);

    assertThatThrownBy(() -> role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM, null))
        .isInstanceOf(TranscriptionRoleUnusableException.class);
    verifyNoInteractions(client);
  }

  @Test
  void aFailedCallMovesTheRoleToUnreachable() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any(), any())).thenReturn(null);
    role.refresh();
    when(client.transcribe(any(), any(), any(), any(), any()))
        .thenThrow(new TranscriptionUnavailableException("connection refused", false));

    assertThatThrownBy(() -> role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM, null))
        .isInstanceOf(TranscriptionUnavailableException.class);
    assertThat(role.currentStatus().state()).isEqualTo(OptionalRoleState.UNREACHABLE);
  }

  /** A recording comes from a person: its refusal must not hide dictation from everybody else. */
  @Test
  void aRecordingTheEndpointRefusesLeavesTheRoleReady() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any(), any())).thenReturn(null);
    role.refresh();
    when(client.transcribe(any(), any(), any(), any(), any()))
        .thenThrow(new TranscriptionUnavailableException("HTTP 400", false, true))
        .thenThrow(new TranscriptionUnavailableException("HTTP 500", false, true))
        .thenThrow(new TranscriptionUnavailableException("no text", false, true));

    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM, null))
          .isInstanceOf(TranscriptionUnavailableException.class);
      assertThat(role.usable()).isTrue();
    }
  }

  @Test
  void aTimedOutCallMovesTheRoleToUnreachable() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any(), any())).thenReturn(null);
    role.refresh();
    when(client.transcribe(any(), any(), any(), any(), any()))
        .thenThrow(new TranscriptionUnavailableException("request timed out", true));

    assertThatThrownBy(() -> role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM, null))
        .isInstanceOf(TranscriptionUnavailableException.class);
    assertThat(role.currentStatus().state()).isEqualTo(OptionalRoleState.UNREACHABLE);
    assertThat(role.currentStatus().timedOut()).isTrue();
  }

  @Test
  void theSlotsAreCappedForTheWholeInstallationAndFreedOnClose() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);

    TranscriptionModelRole.Slot first = role.reserve();
    TranscriptionModelRole.Slot second = role.reserve();
    assertThatThrownBy(role::reserve).isInstanceOf(TranscriptionRoleBusyException.class);

    first.close();
    first.close();
    TranscriptionModelRole.Slot third = role.reserve();
    assertThatThrownBy(role::reserve).isInstanceOf(TranscriptionRoleBusyException.class);
    second.close();
    third.close();
  }

  @Test
  void aSuccessfulCallReturnsTheText() {
    TranscriptionModelRole role = role(endpoint(true, "http://stt:8000/v1", "whisper"), true);
    when(client.probe(any(), any())).thenReturn(null);
    role.refresh();
    when(client.transcribe(any(), any(), any(), any(), any())).thenReturn("Hallo");

    assertThat(role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM, null)).isEqualTo("Hallo");
    assertThat(role.usable()).isTrue();
  }

  @Test
  void sendsBothVocabularyListsAsOnePromptThePersonalOneLast() {
    TranscriptionEndpoint endpoint =
        new TranscriptionEndpoint(true, "http://stt:8000/v1", "whisper", "key", "de", "AO, BZSt");
    TranscriptionModelRole role = role(endpoint, true);
    when(client.probe(any(), any())).thenReturn(null);
    role.refresh();
    when(client.transcribe(any(), any(), any(), any(), any())).thenReturn("Hallo");

    role.transcribe(AUDIO, TranscriptionAudioFormat.WEBM, "Referat Z 4");

    verify(client).transcribe(any(), any(), any(), any(), eq("AO, BZSt, Referat Z 4"));
  }
}
