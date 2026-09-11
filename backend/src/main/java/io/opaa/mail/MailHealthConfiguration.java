package io.opaa.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link MailHealthGroup}; conditional on the same switch as the indicator itself. */
@Configuration
class MailHealthConfiguration {

  @Bean
  @ConditionalOnProperty(name = "management.health.mail.enabled", matchIfMissing = true)
  HealthEndpointGroupsPostProcessor mailHealthGroup() {
    return new MailHealthGroup();
  }
}
