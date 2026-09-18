package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.RateLimitProperties.EndpointLimit;
import io.opaa.auth.local.LocalSelfServiceAvailability;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.security.TrustedProxyClientIpResolver;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wiring of the two switchable self-service rules (#1592), which {@code RateLimitFilterTest}
 * cannot see: that each of them asks its <em>own</em> flow, and that a served flow is still counted
 * - the rules became conditional, so "still counted" is no longer a given. No Spring context; the
 * production {@code @Bean} method is called directly with the shipped defaults (five requests per
 * client and hour for either endpoint).
 */
class RateLimitConfigurationTest {

  private static final String REGISTER = "/api/v1/auth/local/register";
  private static final String FORGOT_PASSWORD = "/api/v1/auth/local/forgot-password";
  private static final int SHIPPED_BUDGET = 5;
  private static final String CLIENT = "203.0.113.7";

  private boolean passwordReset;
  private boolean selfRegistration;

  @Test
  void eachSelfServiceRuleCountsOnlyWhileItsOwnFlowIsServed() throws Exception {
    Filter filter = filter();
    selfRegistration = true;

    // the served flow is counted, the switched-off one next to it is not - a swapped pair of
    // suppliers would show up right here
    assertThat(statusAfter(filter, REGISTER, SHIPPED_BUDGET + 1)).isEqualTo(429);
    assertThat(statusAfter(filter, FORGOT_PASSWORD, SHIPPED_BUDGET + 1)).isEqualTo(200);

    // switched on later, the budget is untouched: nothing was spent while it was off
    passwordReset = true;
    assertThat(statusAfter(filter, FORGOT_PASSWORD, SHIPPED_BUDGET)).isEqualTo(200);
    assertThat(statusAfter(filter, FORGOT_PASSWORD, 1)).isEqualTo(429);
  }

  /** The status of the last of {@code count} requests from one client; earlier ones are ignored. */
  private static int statusAfter(Filter filter, String path, int count) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    for (int i = 0; i < count; i++) {
      response = new MockHttpServletResponse();
      MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
      request.setRemoteAddr(CLIENT);
      filter.doFilter(request, response, new MockFilterChain());
    }
    return response.getStatus();
  }

  private Filter filter() {
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    RateLimitProperties properties =
        new RateLimitProperties(true, List.of(), wide, wide, wide, wide, wide, null);
    return new RateLimitConfiguration()
        .rateLimitFilterRegistration(
            properties,
            new TrustedProxyClientIpResolver(List.of()),
            new RateLimitMetrics(new SimpleMeterRegistry()),
            JsonMapper.builder().build(),
            availability())
        .getFilter();
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<LocalSelfServiceAvailability> availability() {
    LocalSelfServiceAvailability flows =
        new LocalSelfServiceAvailability() {

          @Override
          public boolean isPasswordResetAvailable() {
            return passwordReset;
          }

          @Override
          public boolean isSelfRegistrationAvailable() {
            return selfRegistration;
          }
        };
    ObjectProvider<LocalSelfServiceAvailability> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(flows);
    return provider;
  }
}
