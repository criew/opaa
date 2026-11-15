package io.opaa.health;

import io.opaa.llm.rerank.RerankRoleStatusProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The rerank role in its own health group {@code rerank-model}. */
@Component
@ConditionalOnProperty(name = "management.health.rerank.enabled", matchIfMissing = true)
public class RerankHealthIndicator extends OptionalRoleHealthIndicator {

  public RerankHealthIndicator(RerankRoleStatusProvider role) {
    super(role, ModelHealthGroupsConfiguration.RERANK_GROUP);
  }
}
