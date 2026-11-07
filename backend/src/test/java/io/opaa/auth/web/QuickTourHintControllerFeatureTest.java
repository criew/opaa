package io.opaa.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.auth.QuickTourHint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Switched off, the quick tour has no route to set the marker (ADR-0044, Entscheidung 6): a request
 * answers like any unknown address and writes nothing. Without configuration the route is there.
 */
class QuickTourHintControllerFeatureTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(QuickTourHint.class, () -> mock(QuickTourHint.class))
          .withUserConfiguration(QuickTourHintController.class);

  @Test
  void theControllerIsRegisteredWithoutConfiguration() {
    runner.run(context -> assertThat(context).hasSingleBean(QuickTourHintController.class));
  }

  @Test
  void theControllerIsNotRegisteredWhenTheFlagIsOff() {
    runner
        .withPropertyValues("opaa.features.quick-tour.enabled=false")
        .run(
            context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(QuickTourHintController.class));
  }
}
