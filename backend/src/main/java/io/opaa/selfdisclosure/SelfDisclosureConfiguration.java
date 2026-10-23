package io.opaa.selfdisclosure;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.SelfDisclosureMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The properties and metrics of the self-disclosure. */
@Configuration
@EnableConfigurationProperties(SelfDisclosureProperties.class)
public class SelfDisclosureConfiguration {

  @Bean
  SelfDisclosureMetrics selfDisclosureMetrics(
      MeterRegistry meterRegistry, SelfDisclosureRunRegistry registry) {
    return new SelfDisclosureMetrics(meterRegistry, registry::active);
  }
}
