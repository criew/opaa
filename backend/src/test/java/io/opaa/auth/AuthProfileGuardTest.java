package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthProfileGuardTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(AuthProfileGuard.class);

  @Test
  void refusesToStartWithoutAnAuthProfile() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("No authentication profile is active"));
  }

  @Test
  void startsWithTheOidcProfile() {
    contextRunner
        .withPropertyValues("spring.profiles.active=oidc")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void startsWithTheDevProfile() {
    contextRunner
        .withPropertyValues("spring.profiles.active=dev")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
