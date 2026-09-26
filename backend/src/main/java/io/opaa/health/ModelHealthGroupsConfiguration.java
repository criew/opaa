package io.opaa.health;

import io.opaa.observability.SeparateHealthGroup;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gives each of the three indicators that depend on a model endpoint or the vector store a health
 * group of its own instead of a share of the overall status (#1710). Each is a separate group, not
 * one common one, because each probe costs a call to a different backend - {@code
 * /actuator/health/embedding-model} embeds, {@code /actuator/health/vector-store} searches - and an
 * operator has to be able to ask exactly one of them.
 *
 * <p>Each bean carries the same switch as its indicator, so switching an indicator off leaves no
 * empty group behind.
 */
@Configuration
class ModelHealthGroupsConfiguration {

  static final String CHAT_CONTRIBUTOR = "chat";
  static final String CHAT_GROUP = "chat-model";
  static final String EMBEDDINGS_CONTRIBUTOR = "embeddings";
  static final String EMBEDDINGS_GROUP = "embedding-model";
  static final String VECTOR_STORE_CONTRIBUTOR = "vectorStore";
  static final String VECTOR_STORE_GROUP = "vector-store";

  @Bean
  @ConditionalOnProperty(name = "management.health.chat.enabled", matchIfMissing = true)
  HealthEndpointGroupsPostProcessor chatHealthGroup() {
    return new SeparateHealthGroup(CHAT_GROUP, Set.of(CHAT_CONTRIBUTOR));
  }

  @Bean
  @ConditionalOnProperty(name = "management.health.embeddings.enabled", matchIfMissing = true)
  HealthEndpointGroupsPostProcessor embeddingsHealthGroup() {
    return new SeparateHealthGroup(EMBEDDINGS_GROUP, Set.of(EMBEDDINGS_CONTRIBUTOR));
  }

  @Bean
  @ConditionalOnProperty(name = "management.health.vectorstore.enabled", matchIfMissing = true)
  HealthEndpointGroupsPostProcessor vectorStoreHealthGroup() {
    return new SeparateHealthGroup(VECTOR_STORE_GROUP, Set.of(VECTOR_STORE_CONTRIBUTOR));
  }
}
