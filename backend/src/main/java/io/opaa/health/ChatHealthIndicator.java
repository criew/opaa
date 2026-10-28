package io.opaa.health;

import io.opaa.llm.ActiveChatModelDescription;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.NoActiveChatModelException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the systemwide active chat model
 * (docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung), not the static
 * Spring AI OpenAI autoconfiguration - a health check that names a different model than the one
 * actually answering would be worse than none at all. {@link
 * ActiveChatModelResolver#resolveDescription()} reads {@code llm_models} directly rather than
 * building a full {@code ChatClient}, so this check stays cheap and never requires the settings
 * encryption key to be configured just to report a base URL and a model identifier - it never
 * contacts the model itself. Without an active model the state is {@code UNKNOWN} with {@code
 * chatModel: NOT_CONFIGURED}: an installation that has not set its chat model yet is no outage.
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
    } catch (NoActiveChatModelException e) {
      return Health.unknown()
          .withDetail("chatModel", EmbeddingsHealthIndicator.NOT_CONFIGURED)
          .build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
