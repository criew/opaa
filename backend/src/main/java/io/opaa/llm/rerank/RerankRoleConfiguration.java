package io.opaa.llm.rerank;

import io.opaa.llm.ModelRoleSettingsChangedEvent;
import io.opaa.llm.OptionalModelRole;
import io.opaa.llm.OptionalRoleStartupCheck;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

/** The rerank role's load limits and its own probe thread. */
@Configuration
@EnableConfigurationProperties(RerankProperties.class)
public class RerankRoleConfiguration {

  /** The role's own probe thread ({@link OptionalModelRole#probeScheduler}). */
  @Bean(destroyMethod = "shutdown")
  TaskScheduler rerankProbeScheduler() {
    return OptionalModelRole.probeScheduler("rerank-probe-");
  }

  @Bean
  OptionalRoleStartupCheck rerankRoleStartupCheck(
      RerankModelRole role,
      @Qualifier("rerankProbeScheduler") TaskScheduler probeScheduler,
      Clock clock) {
    return new OptionalRoleStartupCheck(
        role,
        ModelRoleSettingsChangedEvent.Role.RERANK,
        "Rerank",
        "Retrieval continues without reranking; the state stays readable via"
            + " RerankRoleStatusProvider#currentStatus.",
        probeScheduler,
        clock);
  }
}
