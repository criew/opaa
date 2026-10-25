package io.opaa.installation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.common.InstallationStatus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the installation and reports its mode (ADR-0060, Entscheidung 4): the health component
 * {@code installation} is always {@code UP}, so the readiness stays {@code UP} and the setup stays
 * reachable, and names the mode in its details; the gauge {@code opaa.installation.pending} is
 * {@code 1} while pending and {@code 0} afterwards.
 */
@Configuration
@EnableConfigurationProperties(InstallationProperties.class)
class InstallationConfiguration {

  static final String PENDING_GAUGE = "opaa.installation.pending";

  @Bean
  HealthIndicator installationHealthIndicator(InstallationStatus status) {
    return () -> Health.up().withDetail("pending", status.isPending()).build();
  }

  @Bean
  Gauge installationPendingGauge(MeterRegistry registry, InstallationStatus status) {
    return Gauge.builder(PENDING_GAUGE, status, s -> s.isPending() ? 1 : 0)
        .description("1 while the installation of this OPAA instance is pending, else 0")
        .register(registry);
  }
}
