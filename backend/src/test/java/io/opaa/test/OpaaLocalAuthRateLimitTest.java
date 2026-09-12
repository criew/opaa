package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OpaaLocalAuthMockMvcTest} with the production rate limits of the local sign-in restored
 * (ADR-0033, Entscheidung 9) and a trusted proxy, so the limiter resolves the forwarded address.
 *
 * <p><b>Why this cannot be the base signature:</b> the base raises every local-auth limit to 100000
 * because all of its classes drive their sign-ins from MockMvc's single address and would otherwise
 * exhaust a 10-per-minute budget within one class. The limits are the subject here, so they have to
 * be the real ones - the same relationship {@link OpaaPropertyVariantIntegrationTest} has to the
 * canonical signature.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaLocalAuthMockMvcTest
@TestPropertySource(
    properties = {
      "opaa.rate-limit.trusted-proxy-cidrs=10.0.0.0/8",
      "opaa.rate-limit.local-auth.login.max-requests=10",
      "opaa.rate-limit.local-auth.login.window-seconds=60",
      "opaa.rate-limit.local-auth.login.global-max-requests=1000",
      "opaa.rate-limit.local-auth.refresh.max-requests=3",
      "opaa.rate-limit.local-auth.change-password.max-requests=5",
      "opaa.rate-limit.local-auth.change-password.window-seconds=300"
    })
public @interface OpaaLocalAuthRateLimitTest {}
