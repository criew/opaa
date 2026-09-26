package io.opaa.api;

import io.opaa.auth.local.LocalSelfServiceAvailability;
import io.opaa.ratelimit.SelfServiceEndpointAvailability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands the auth side's answer on the switchable self-service flows to the rate limits. The flow
 * bean is looked up on every call, never at wiring time, and its absence reads as {@link
 * LocalSelfServiceAvailability#NONE}.
 */
@Configuration
class SelfServiceRateLimitConfiguration {

  @Bean
  SelfServiceEndpointAvailability selfServiceEndpointAvailability(
      ObjectProvider<LocalSelfServiceAvailability> selfServiceFlows) {
    return new SelfServiceEndpointAvailability() {

      @Override
      public boolean isPasswordResetAvailable() {
        return current().isPasswordResetAvailable();
      }

      @Override
      public boolean isSelfRegistrationAvailable() {
        return current().isSelfRegistrationAvailable();
      }

      private LocalSelfServiceAvailability current() {
        return selfServiceFlows.getIfAvailable(() -> LocalSelfServiceAvailability.NONE);
      }
    };
  }
}
