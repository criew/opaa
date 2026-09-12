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
 * {@link OpaaIntegrationTest} runs {@code local,dev}, where {@code DevAuthFilter} authenticates
 * every request before a bearer token is ever read, so it cannot exercise a local session at all -
 * that is the hard technical reason this fifth signature exists next to the four of #1481.
 *
 * <p>Every class carrying this exact signature shares one Spring context and one Postgres (Issue
 * #843); no {@code OPAA_OIDC_*} bootstrap is set, so {@code OidcProviderSeeder} seeds nothing and
 * the LOCAL provider row is created by the test itself ({@link LocalAccountFixtures}).
 *
 * <p>Three variants are meta-annotated over this one and add only the properties that are
 * themselves the subject under test, the way {@link OpaaPropertyVariantIntegrationTest} does for
 * the canonical signature: {@link OpaaLocalAuthLinkTest} (a public base URL - classes on the base
 * signature prove the link flows are off <em>without</em> one), {@link OpaaLocalAuthSeedTest} (a
 * deliverable initial administrator address - the base carries the shipped default, which the seed
 * refuses) and {@link OpaaLocalAuthRateLimitTest} (the production rate limits - the base raises
 * them, see below). Each is listed in {@code SpringContextSignatureTest}.
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
      "opaa.rate-limit.local-auth.change-password.max-requests=100000",
      "opaa.rate-limit.local-auth.register.max-requests=100000",
      "opaa.rate-limit.local-auth.register.global-max-requests=100000",
      "opaa.rate-limit.local-auth.register.max-requests-per-address=100000",
      "opaa.rate-limit.local-auth.forgot-password.max-requests=100000",
      "opaa.rate-limit.local-auth.forgot-password.global-max-requests=100000",
      "opaa.rate-limit.local-auth.forgot-password.max-requests-per-address=100000",
      "opaa.rate-limit.local-auth.set-password.max-requests=100000",
      "opaa.rate-limit.local-auth.verify-email.max-requests=100000"
    })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("oidc")
@Testcontainers(disabledWithoutDocker = true)
public @interface OpaaLocalAuthMockMvcTest {

  /** A public, deliberately non-production secret that passes {@code @ValidSecret}. */
  String JWT_SECRET = "mockmvc-local-auth-integration-test-key-0123456789-abcdefghij";
}
