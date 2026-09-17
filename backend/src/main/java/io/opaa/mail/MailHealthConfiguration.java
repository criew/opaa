package io.opaa.mail;

import io.opaa.observability.SeparateHealthGroup;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the mail contributor out of the overall health status and shows it in its own group, {@code
 * /actuator/health/mail} (#1536): a single refused mail must not take an instance out of rotation
 * while chat and search keep working - the same stance ADR-0030, Entscheidung 9 takes for the
 * upload store. Conditional on the same switch as the indicator itself.
 */
@Configuration
class MailHealthConfiguration {

  static final String CONTRIBUTOR = "mailDelivery";
  static final String GROUP = "mail";

  @Bean
  @ConditionalOnProperty(name = "management.health.mail.enabled", matchIfMissing = true)
  HealthEndpointGroupsPostProcessor mailHealthGroup() {
    return new SeparateHealthGroup(GROUP, Set.of(CONTRIBUTOR));
  }
}
