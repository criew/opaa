package io.opaa.prompt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.prompt.PromptLibraryService;
import io.opaa.prompt.PromptService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Switched off, the prompt library's routes are not registered at all (ADR-0044, Entscheidung 6):
 * a request answers like any unknown address. Without configuration both controllers are there.
 */
class PromptControllersFeatureTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(PromptLibraryService.class, () -> mock(PromptLibraryService.class))
          .withBean(PromptService.class, () -> mock(PromptService.class))
          .withUserConfiguration(PromptLibraryController.class, AvailablePromptController.class);

  @Test
  void bothControllersAreRegisteredWithoutConfiguration() {
    runner.run(
        context ->
            assertThat(context)
                .hasSingleBean(PromptLibraryController.class)
                .hasSingleBean(AvailablePromptController.class));
  }

  @Test
  void neitherControllerIsRegisteredWhenTheFlagIsOff() {
    runner
        .withPropertyValues("opaa.features.prompt-libraries.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(PromptLibraryController.class)
                    .doesNotHaveBean(AvailablePromptController.class));
  }
}
