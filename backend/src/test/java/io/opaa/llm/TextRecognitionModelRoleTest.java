package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.TextRecognitionConnectionType;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The two levels of text recognition: the operation's switch (off by default) and the role's own
 * switch. While the operation's switch is off the role is neither evaluated nor contacted; the
 * indexing gets an endpoint only while both agree and something usable is stored.
 */
class TextRecognitionModelRoleTest {

  private static final TextRecognitionEndpoint DOCLING =
      new TextRecognitionEndpoint(
          true, TextRecognitionConnectionType.DOCLING_SERVE, "http://ocr:5001", null, "key");

  private final TextRecognitionClient client = mock(TextRecognitionClient.class);
  private final AtomicReference<TextRecognitionEndpoint> stored = new AtomicReference<>(DOCLING);

  private TextRecognitionModelRole role(boolean operationEnabled) {
    return new TextRecognitionModelRole(
        new TextRecognitionProperties(operationEnabled, 0, null, 0, 0, null), stored::get, client);
  }

  @Test
  void theOperationsSwitchIsOffByDefault() {
    assertThat(TextRecognitionProperties.disabled().enabled()).isFalse();
  }

  @Test
  void switchedOffByTheOperationTheRoleIsNeitherEvaluatedNorContacted() {
    TextRecognitionModelRole role = role(false);

    assertThat(role.active()).isEmpty();
    assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.DISABLED);
    assertThat(role.refresh().state()).isEqualTo(TextRecognitionRoleState.DISABLED);
    role.probePeriodically();
    assertThatThrownBy(
            () ->
                role.convertWithDocling(
                    new TextRecognitionModelRole.ActiveTextRecognition(
                        DOCLING, DOCLING.configurationId()),
                    new byte[] {1}))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(client);
  }

  @Test
  void switchedOffInTheAdministrationNothingIsActive() {
    stored.set(
        new TextRecognitionEndpoint(
            false, TextRecognitionConnectionType.DOCLING_SERVE, "http://ocr:5001", null, "key"));
    TextRecognitionModelRole role = role(true);

    assertThat(role.active()).isEmpty();
    assertThat(role.refresh().state()).isEqualTo(TextRecognitionRoleState.DISABLED);
    verifyNoInteractions(client);
  }

  @Test
  void aVisionModelWithoutAModelIsUnconfigured() {
    stored.set(
        new TextRecognitionEndpoint(
            true, TextRecognitionConnectionType.OPENAI_VISION, "http://vlm/v1", "", "key"));
    TextRecognitionModelRole role = role(true);

    assertThat(role.active()).isEmpty();
    assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.UNCONFIGURED);
  }

  @Test
  void bothSwitchesOnTheIndexingGetsTheEndpointAndItsRecipientId() {
    TextRecognitionModelRole role = role(true);

    TextRecognitionModelRole.ActiveTextRecognition active = role.active().orElseThrow();

    assertThat(active.connectionType()).isEqualTo(TextRecognitionConnectionType.DOCLING_SERVE);
    assertThat(active.configurationId()).isEqualTo(DOCLING.configurationId()).hasSize(16);
    assertThat(active.toString()).doesNotContain("apiKey=key").contains("apiKey=***");
    assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.UNREACHABLE);
  }

  @Test
  void theRecipientIdChangesWithTypeAddressOrModelButNotWithTheKey() {
    String id = DOCLING.configurationId();

    assertThat(
            new TextRecognitionEndpoint(
                    true, TextRecognitionConnectionType.DOCLING_SERVE, "http://ocr:5001", null, "x")
                .configurationId())
        .isEqualTo(id);
    assertThat(
            new TextRecognitionEndpoint(
                    true,
                    TextRecognitionConnectionType.DOCLING_SERVE,
                    "http://ocr:5002",
                    null,
                    "key")
                .configurationId())
        .isNotEqualTo(id);
    assertThat(
            new TextRecognitionEndpoint(
                    true,
                    TextRecognitionConnectionType.OPENAI_VISION,
                    "http://ocr:5001",
                    "m",
                    "key")
                .configurationId())
        .isNotEqualTo(id);
  }

  @Test
  void aProbeThatAnswersMakesTheRoleReady() {
    when(client.probe(eq(DOCLING), any(Duration.class))).thenReturn(null);
    TextRecognitionModelRole role = role(true);

    assertThat(role.refresh().state()).isEqualTo(TextRecognitionRoleState.READY);
    assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.READY);
  }

  @Test
  void aServiceThatIsNotReachedMakesTheRoleUnreachableADocumentRefusalDoesNot() {
    when(client.probe(eq(DOCLING), any(Duration.class))).thenReturn(null);
    TextRecognitionModelRole role = role(true);
    role.refresh();
    TextRecognitionModelRole.ActiveTextRecognition active = role.active().orElseThrow();

    when(client.convertWithDocling(eq(DOCLING), any(), any()))
        .thenThrow(new TextRecognitionClient.TextRecognitionRejectedException("HTTP 422", 422));
    assertThatThrownBy(() -> role.convertWithDocling(active, new byte[] {1}))
        .isInstanceOf(TextRecognitionClient.TextRecognitionRejectedException.class);
    assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.READY);

    when(client.convertWithDocling(eq(DOCLING), any(), any()))
        .thenThrow(
            new TextRecognitionClient.TextRecognitionUnavailableException(
                "connection refused", false, 0));
    assertThatThrownBy(() -> role.convertWithDocling(active, new byte[] {1}))
        .isInstanceOf(TextRecognitionClient.TextRecognitionUnavailableException.class);
    assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.UNREACHABLE);
  }

  @Test
  void aDoclingEndpointIsNotReadAsAVisionModel() {
    TextRecognitionModelRole role = role(true);
    TextRecognitionModelRole.ActiveTextRecognition active = role.active().orElseThrow();

    assertThatThrownBy(() -> role.recognizePage(active, new byte[] {1}, "image/png"))
        .isInstanceOf(IllegalStateException.class);
    verify(client, never()).recognizePageWithVision(any(), any(), any(), any());
  }

  @Test
  void aRefusedKeyOrAServerErrorMakesTheRoleUnreachableWithTheReason() {
    when(client.probe(eq(DOCLING), any(Duration.class)))
        .thenReturn(new TextRecognitionClient.ProbeFailure("HTTP 401", false, true));
    TextRecognitionModelRole role = role(true);

    TextRecognitionRoleStatus probed = role.refresh();

    assertThat(probed.state()).isEqualTo(TextRecognitionRoleState.UNREACHABLE);
    assertThat(probed.diagnostic()).contains("refused the key");

    when(client.probe(eq(DOCLING), any(Duration.class))).thenReturn(null);
    role.refresh();
    TextRecognitionModelRole.ActiveTextRecognition active = role.active().orElseThrow();
    for (int status : new int[] {401, 503}) {
      role.refresh();
      when(client.convertWithDocling(eq(DOCLING), any(), any()))
          .thenThrow(
              new TextRecognitionClient.TextRecognitionUnavailableException(
                  "HTTP " + status, false, status));
      assertThatThrownBy(() -> role.convertWithDocling(active, new byte[] {1}))
          .isInstanceOf(TextRecognitionClient.TextRecognitionUnavailableException.class);
      assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.UNREACHABLE);
      assertThat(role.currentStatus().diagnostic()).isEqualTo("HTTP " + status);
    }

    role.refresh();
    when(client.convertWithDocling(eq(DOCLING), any(), any()))
        .thenThrow(
            new TextRecognitionClient.TextRecognitionUnavailableException("HTTP 429", false, 429));
    assertThatThrownBy(() -> role.convertWithDocling(active, new byte[] {1}))
        .isInstanceOf(TextRecognitionClient.TextRecognitionUnavailableException.class);
    assertThat(role.currentStatus().state()).isEqualTo(TextRecognitionRoleState.READY);
  }
}
