package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.PasswordEncoderConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Startup behaviour of the local-account configuration (ADR-0033 Entscheidung 6), the way {@link
 * io.opaa.auth.AuthProfileGuardTest} proves {@code AuthProfileGuard}: in the {@code oidc} profile
 * a missing, short or placeholder {@code OPAA_AUTH_JWT_SECRET} refuses the start with a message
 * that names the variable and the command that generates one; the {@code dev} profile starts
 * without any secret at all.
 */
class LocalAuthSecretGuardTest {

  private static final String STRONG_SECRET = "local-auth-secret-guard-test-secret-0123456789";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              LocalAuthConfiguration.class,
              LocalAuthSecretGuard.class,
              PasswordEncoderConfiguration.class);

  @Test
  void oidcProfileRefusesToStartWithoutTheSecret() {
    contextRunner
        .withPropertyValues("spring.profiles.active=oidc")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_AUTH_JWT_SECRET")
                    .hasMessageContaining("openssl rand -base64 48"));
  }

  @Test
  void oidcProfileRefusesAPlaceholderSecret() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc", "opaa.auth.local.jwt-secret=change_me")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_AUTH_JWT_SECRET"));
  }

  @Test
  void oidcProfileRefusesAShortSecret() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc", "opaa.auth.local.jwt-secret=nur-31-zeichen-lang-ist-zu-kurz")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_AUTH_JWT_SECRET")
                    .hasMessageContaining("32"));
  }

  @Test
  void oidcProfileStartsWithAStrongSecretAndExposesTheCryptoBeans() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc", "opaa.auth.local.jwt-secret=" + STRONG_SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(LocalAuthKeyService.class);
              assertThat(context).hasSingleBean(PasswordEncoder.class);
              assertThat(context.getBean(LocalAuthProperties.class).jwtSecret())
                  .isEqualTo(STRONG_SECRET);
            });
  }

  @Test
  void oidcProfileRefusesAnOutOfRangeSessionLimitEvenWithAStrongSecret() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc",
            "opaa.auth.local.jwt-secret=" + STRONG_SECRET,
            "opaa.auth.local.refresh-token-ttl=31d")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_AUTH_LOCAL_REFRESH_TOKEN_TTL"));
  }

  @Test
  void devProfileStartsWithoutTheSecret() {
    contextRunner
        .withPropertyValues("spring.profiles.active=dev")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(LocalAuthKeyService.class);
            });
  }

  @Test
  void devProfileIgnoresAWeakSecret() {
    contextRunner
        .withPropertyValues("spring.profiles.active=dev", "opaa.auth.local.jwt-secret=opaa")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
