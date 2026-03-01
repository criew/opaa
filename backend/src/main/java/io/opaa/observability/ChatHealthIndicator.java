package io.opaa.observability;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "management.health.chat.enabled", matchIfMissing = true)
public class ChatHealthIndicator implements HealthIndicator {

  private final ChatModel chatModel;

  public ChatHealthIndicator(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  @Override
  public Health health() {
    try {
      String provider = chatModel.getClass().getSimpleName();
      return Health.up().withDetail("provider", provider).build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
