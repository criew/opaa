package io.opaa.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.AuthMetrics;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
   * Backs {@link UserService}'s {@code lastLoginAt} write-throttling (#833) - a named bean, not
   * {@code Clock.systemDefaultZone()} called directly there, mirroring {@code
   * IndexingConfiguration#schedulingClock} so a test can substitute a fixed clock without needing
   * to control wall-clock time. Named {@code clock} (matching the constructor parameter name in
   * {@link UserService}, not {@code schedulingClock}) so Spring's by-name fallback picks this bean
   * over that unrelated one when resolving the {@link Clock} type, which now has two candidates in
   * the context.
   */
  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }
}
