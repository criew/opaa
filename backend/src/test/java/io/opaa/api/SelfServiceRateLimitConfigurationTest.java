package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.auth.local.LocalSelfServiceAvailability;
import io.opaa.ratelimit.SelfServiceEndpointAvailability;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The bridge from the auth side's self-service flows to the rate limits: each endpoint answers from
 * its own flow, the flow is asked on every call, and a missing flow bean reads as not served.
 */
class SelfServiceRateLimitConfigurationTest {

  private boolean passwordReset;
  private boolean selfRegistration;

  @Test
  void eachEndpointAnswersFromItsOwnFlowOnEveryCall() {
    SelfServiceEndpointAvailability endpoints = bridge(provider(flows()));

    selfRegistration = true;
    assertThat(endpoints.isSelfRegistrationAvailable()).isTrue();
    assertThat(endpoints.isPasswordResetAvailable()).isFalse();

    selfRegistration = false;
    passwordReset = true;
    assertThat(endpoints.isSelfRegistrationAvailable()).isFalse();
    assertThat(endpoints.isPasswordResetAvailable()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aMissingFlowBeanReadsAsNotServed() {
    ObjectProvider<LocalSelfServiceAvailability> absent = mock(ObjectProvider.class);
    when(absent.getIfAvailable(any()))
        .thenAnswer(
            invocation -> invocation.<Supplier<LocalSelfServiceAvailability>>getArgument(0).get());

    SelfServiceEndpointAvailability endpoints = bridge(absent);

    assertThat(endpoints.isSelfRegistrationAvailable()).isFalse();
    assertThat(endpoints.isPasswordResetAvailable()).isFalse();
  }

  private static SelfServiceEndpointAvailability bridge(
      ObjectProvider<LocalSelfServiceAvailability> flows) {
    return new SelfServiceRateLimitConfiguration().selfServiceEndpointAvailability(flows);
  }

  private LocalSelfServiceAvailability flows() {
    return new LocalSelfServiceAvailability() {

      @Override
      public boolean isPasswordResetAvailable() {
        return passwordReset;
      }

      @Override
      public boolean isSelfRegistrationAvailable() {
        return selfRegistration;
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<LocalSelfServiceAvailability> provider(
      LocalSelfServiceAvailability flows) {
    ObjectProvider<LocalSelfServiceAvailability> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(flows);
    return provider;
  }
}
