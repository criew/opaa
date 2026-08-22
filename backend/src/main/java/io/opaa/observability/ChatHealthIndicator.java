package io.opaa.observability;

import io.opaa.llm.ActiveChatModelDescription;
import io.opaa.llm.ActiveChatModelResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the systemwide active chat model (#758,
 * docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung), not the static
 * Spring AI OpenAI autoconfiguration this indicator used to introspect - a health check that names
 * a different model than the one actually answering would be worse than none at all (see the
 * issue's own motivation). {@link ActiveChatModelResolver#resolveDescription()} reads {@code
 * llm_models} directly rather than building a full {@code ChatClient}, so this check stays cheap
 * and never requires the settings encryption key to be configured just to report a base URL and a
 * model identifier - it never contacts the model itself, exactly as the previous implementation
 * never did.
 */
@Component
@ConditionalOnProperty(name = "management.health.chat.enabled", matchIfMissing = true)
public class ChatHealthIndicator implements HealthIndicator {

  private final ActiveChatModelResolver activeChatModelResolver;

  public ChatHealthIndicator(ActiveChatModelResolver activeChatModelResolver) {
    this.activeChatModelResolver = activeChatModelResolver;
  }

  @Override
  public Health health() {
    try {
      ActiveChatModelDescription description = activeChatModelResolver.resolveDescription();
      return Health.up()
          .withDetail("baseUrl", description.baseUrl())
          .withDetail("modelIdentifier", description.modelIdentifier())
          .build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
