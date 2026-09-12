package io.opaa.auth.local;

import io.opaa.common.PublicBaseUrl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link PublicBaseUrlAvailability} to {@link PublicBaseUrl}: whether {@code
 * OPAA_PUBLIC_BASE_URL} is set is the precondition of every link flow, read by the sign-in
 * configuration and the settings of the local account management. A configuration of its own, so
 * {@link LocalAuthConfiguration} keeps loading without the common package (its secret-guard test).
 */
@Configuration
public class PublicBaseUrlAvailabilityConfiguration {

  @Bean
  public PublicBaseUrlAvailability publicBaseUrlAvailability(PublicBaseUrl publicBaseUrl) {
    return publicBaseUrl::isConfigured;
  }
}
