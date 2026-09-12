package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.RateLimitProperties;
import io.opaa.api.RateLimitProperties.LocalAuthLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimits;
import io.opaa.auth.local.LocalAuthRateLimiter.AddressScope;
import io.opaa.common.TooManyRequestsException;
import io.opaa.observability.RateLimitMetrics;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalAuthRateLimiter} (ADR-0033, Entscheidung 9): the limits that need more than the
 * request path - the password change per authenticated subject, registration and password reset per
 * address (#1538). A refusal is a {@link TooManyRequestsException} with the seconds to wait;
 * addresses are keyed case-insensitively and never stored as text.
 */
class LocalAuthRateLimiterTest {

  private static final RateLimitProperties.EndpointLimit ANY =
      new RateLimitProperties.EndpointLimit(1, 1, 1);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private LocalAuthRateLimiter limiter(boolean enabled) {
    LocalAuthLimits limits =
        new LocalAuthLimits(
            null,
            null,
            new LocalAuthLimit(2, 300, null, null),
            new LocalAuthLimit(5, 3600, 50, 2),
            new LocalAuthLimit(5, 3600, 50, 1),
            null,
            null);
    return new LocalAuthRateLimiter(
        new RateLimitProperties(enabled, null, ANY, ANY, ANY, ANY, ANY, limits),
        new RateLimitMetrics(meterRegistry));
  }

  private double rejected(String limit, String scope) {
    return meterRegistry
        .counter(RateLimitMetrics.REJECTED_METRIC, "limit", limit, "scope", scope)
        .count();
  }

  @Test
  void thePasswordChangeIsLimitedPerSubject() {
    LocalAuthRateLimiter limiter = limiter(true);
    UUID erika = UUID.randomUUID();
    UUID max = UUID.randomUUID();

    limiter.requireChangePasswordAllowance(erika);
    limiter.requireChangePasswordAllowance(erika);
    assertThatThrownBy(() -> limiter.requireChangePasswordAllowance(erika))
        .isInstanceOf(TooManyRequestsException.class)
        .satisfies(
            e -> assertThat(((TooManyRequestsException) e).retryAfterSeconds()).isBetween(1L, 300L))
        .hasMessage(TooManyRequestsException.MESSAGE);
    assertThatCode(() -> limiter.requireChangePasswordAllowance(max)).doesNotThrowAnyException();
    assertThat(rejected("local-auth-change-password", "subject")).isEqualTo(1.0);
  }

  @Test
  void addressesAreKeyedCaseInsensitivelyAndPerScope() {
    LocalAuthRateLimiter limiter = limiter(true);

    limiter.requireAddressAllowance(AddressScope.REGISTER, "Erika.Muster@Stadt.Example");
    limiter.requireAddressAllowance(AddressScope.REGISTER, "  erika.muster@stadt.example ");
    assertThatThrownBy(
            () ->
                limiter.requireAddressAllowance(
                    AddressScope.REGISTER, "erika.muster@stadt.example"))
        .isInstanceOf(TooManyRequestsException.class);
    // the same address in the other scope has its own budget
    assertThatCode(
            () ->
                limiter.requireAddressAllowance(
                    AddressScope.FORGOT_PASSWORD, "erika.muster@stadt.example"))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                limiter.requireAddressAllowance(
                    AddressScope.FORGOT_PASSWORD, "erika.muster@stadt.example"))
        .isInstanceOf(TooManyRequestsException.class);
    assertThat(rejected("local-auth-register", "address")).isEqualTo(1.0);
    assertThat(rejected("local-auth-forgot-password", "address")).isEqualTo(1.0);
  }

  @Test
  void aBlankAddressIsNotCountedAtAll() {
    LocalAuthRateLimiter limiter = limiter(true);

    for (int i = 0; i < 5; i++) {
      limiter.requireAddressAllowance(AddressScope.REGISTER, "   ");
      limiter.requireAddressAllowance(AddressScope.REGISTER, null);
    }
    assertThat(rejected("local-auth-register", "address")).isZero();
  }

  @Test
  void withRateLimitingDisabledNothingIsRefused() {
    LocalAuthRateLimiter limiter = limiter(false);
    UUID subject = UUID.randomUUID();

    for (int i = 0; i < 10; i++) {
      limiter.requireChangePasswordAllowance(subject);
      limiter.requireAddressAllowance(AddressScope.FORGOT_PASSWORD, "erika@stadt.example");
    }
    assertThat(rejected("local-auth-change-password", "subject")).isZero();
  }
}
