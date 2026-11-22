package io.opaa.query.toolcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.observability.OutboundMetrics;
import io.opaa.observability.OutboundTarget;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** Every call of a tool check counts as one outbound call to {@code model_check}. */
class MeteredCheckChatModelTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ChatModel delegate = mock(ChatModel.class);
  private final MeteredCheckChatModel model =
      new MeteredCheckChatModel(
          delegate, new OutboundMetrics(meters, OutboundTarget.MODEL_CHECK, null));

  @Test
  void aSuccessfulCallCountsAsSuccessOnTheBackgroundPath() {
    when(delegate.call(any(Prompt.class))).thenReturn(new ChatResponse(java.util.List.of()));

    model.call(new Prompt("bereit"));

    assertThat(calls("success")).isEqualTo(1);
  }

  @Test
  void aFailedCallCountsByItsClassAndIsRethrown() {
    when(delegate.call(any(Prompt.class)))
        .thenThrow(new RuntimeException(new SocketTimeoutException("read timed out")));

    assertThatThrownBy(() -> model.call(new Prompt("bereit"))).isInstanceOf(RuntimeException.class);

    assertThat(calls("timeout")).isEqualTo(1);
  }

  private double calls(String outcome) {
    return meters
        .get(OutboundMetrics.REQUESTS)
        .tag("target", "model_check")
        .tag("outcome", outcome)
        .tag("path_class", "background")
        .timer()
        .count();
  }
}
