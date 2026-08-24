package io.opaa.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.AuthMetrics;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires {@link AuthMetrics} the same way {@code IndexingConfiguration}/{@code QueryConfiguration}
 * wire their own {@code io.opaa.observability} metrics beans (#307 review, finding 3) - a plain
 * {@code @Bean} method, not a {@code @Component} on the metrics class itself, so the class stays a
 * simple constructor-injected collaborator rather than something Spring auto-detects.
 */
@Configuration
public class AuthConfiguration {

  @Bean
  AuthMetrics authMetrics(MeterRegistry meterRegistry) {
    return new AuthMetrics(meterRegistry);
  }

  /**
   * General-purpose wall clock, {@code @Primary} over {@code IndexingConfiguration#schedulingClock}
   * so any future unqualified {@link Clock} injection resolves here instead of hitting a {@code
   * NoUniqueBeanDefinitionException}. Backs {@link UserService}'s {@code lastLoginAt}
   * write-throttling (#833).
   */
  @Bean
  @Primary
  Clock clock() {
    return Clock.systemDefaultZone();
  }
}
