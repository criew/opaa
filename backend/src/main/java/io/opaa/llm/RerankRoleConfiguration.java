package io.opaa.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the fallback {@link RerankRoleStatusProvider} for a deployment in which the rerank
 * role itself (#1050) is not built yet.
 *
 * <p>{@code @ConditionalOnMissingBean}: as soon as #1050 registers its own provider, this one
 * disappears without any call site changing. The fallback still reads {@code opaa.rerank.enabled}
 * ({@code OPAA_RERANK_ENABLED}), so a deployment that switches the role on before it exists is told
 * so as a Störung rather than being shown a reassuring "aus".
 */
@Configuration
public class RerankRoleConfiguration {

  @Bean
  @ConditionalOnMissingBean(RerankRoleStatusProvider.class)
  RerankRoleStatusProvider unbuiltRerankRoleStatusProvider(
      @Value("${opaa.rerank.enabled:false}") boolean rerankEnabled) {
    return () ->
        rerankEnabled
            ? new RerankRoleStatus(
                RerankRoleState.UNCONFIGURED,
                null,
                null,
                "rerank role switched on, but no rerank model role is built in this deployment")
            : RerankRoleStatus.disabled();
  }
}
