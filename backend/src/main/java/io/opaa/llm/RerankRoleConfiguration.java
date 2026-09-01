package io.opaa.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the fallback {@link RerankRoleStatusProvider} for a deployment in which the rerank
 * role itself (#1050) is not built yet. The fallback reads {@code opaa.rerank.enabled} ({@code
 * OPAA_RERANK_ENABLED}), so a deployment that switches the role on before it exists is told so as a
 * Störung rather than being shown a reassuring "aus".
 *
 * <p><b>The real provider must be a stereotype bean</b> ({@code @Component}/{@code @Service} on the
 * implementing class), not a {@code @Bean} method of a second {@code @Configuration} - see {@link
 * RerankRoleStatusProvider}. This class is component-scanned, not auto-configured, so
 * {@code @ConditionalOnMissingBean} only sees definitions registered before it is evaluated, and
 * the order of two scanned {@code @Configuration} classes is undefined. A stereotype bean is
 * registered during scanning and is therefore always visible here.
 *
 * <p>The fallback logs its own activation, because the one failure mode the condition cannot rule
 * out is silent: if #1050's provider is itself conditional and its condition does not hold, this
 * fallback steps in and reports "ausdrücklich abgeschaltet" - exactly the reassuring answer the
 * contract exists to prevent. The log line is what makes that case findable.
 */
@Configuration
public class RerankRoleConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RerankRoleConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(RerankRoleStatusProvider.class)
  RerankRoleStatusProvider unbuiltRerankRoleStatusProvider(
      @Value("${opaa.rerank.enabled:false}") boolean rerankEnabled) {
    log.info(
        "No RerankRoleStatusProvider bean present, using the fallback provider; rerank role is"
            + " reported from configuration only (opaa.rerank.enabled={})",
        rerankEnabled);
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
