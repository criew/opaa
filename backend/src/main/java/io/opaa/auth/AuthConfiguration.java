package io.opaa.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.AuthMetrics;
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
}
