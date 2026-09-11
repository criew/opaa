package io.opaa.test;

import io.opaa.TestcontainersConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Canonical context signature for tests that drive the local token issuer through MockMvc (ADR-0033
 * Entscheidung 8, #1533): the {@code oidc} profile - the only one in which the issuer, its
 * endpoints and the {@code pcr} filter exist - over the shared {@link TestcontainersConfiguration}
 * Postgres, with a strong test secret so {@code LocalAuthSecretGuard} lets the context start.
 * {@link OpaaMockMvcTest} runs {@code dev}, where {@code DevAuthFilter} authenticates every request
 * before a bearer token is ever read, so it cannot exercise a local session at all.
 *
 * <p>Every class carrying this exact signature shares one Spring context and one Postgres (Issue
 * #843); no {@code OPAA_OIDC_*} bootstrap is set, so {@code OidcProviderSeeder} seeds nothing and
 * the LOCAL provider row is created by the test itself ({@link LocalAccountFixtures}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
// The local-auth limits (#1535) are widened for this shared context: every class drives its
// sign-ins from MockMvc's one address, and the production defaults (login 10/60 s) would exhaust
// within a single class. LocalAuthRateLimitIntegrationTest proves the limits in its own context.
@SpringBootTest(
    properties = {
      "opaa.auth.local.jwt-secret=" + OpaaLocalAuthMockMvcTest.JWT_SECRET,
      "opaa.rate-limit.local-auth.login.max-requests=100000",
      "opaa.rate-limit.local-auth.login.global-max-requests=100000",
      "opaa.rate-limit.local-auth.refresh.max-requests=100000",
      "opaa.rate-limit.local-auth.change-password.max-requests=100000"
    })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("oidc")
@Testcontainers(disabledWithoutDocker = true)
public @interface OpaaLocalAuthMockMvcTest {

  /** A public, deliberately non-production secret that passes {@code @ValidSecret}. */
  String JWT_SECRET = "mockmvc-local-auth-integration-test-key-0123456789-abcdefghij";
}
