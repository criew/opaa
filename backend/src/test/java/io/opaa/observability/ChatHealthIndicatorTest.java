package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class ChatHealthIndicatorTest {

  @Test
  void reportsUpWhenChatModelIsAvailable() {
    ChatModel chatModel = mock(ChatModel.class);
    ChatHealthIndicator indicator = new ChatHealthIndicator(chatModel);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsKey("provider");
  }
}
