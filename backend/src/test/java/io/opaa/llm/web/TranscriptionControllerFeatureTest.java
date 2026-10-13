package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.llm.TranscriptionModelRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Switched off, the dictation routes are not registered at all (ADR-0044): a request answers like
 * any unknown address. Without configuration the controller is there.
 */
class TranscriptionControllerFeatureTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(TranscriptionModelRole.class, () -> mock(TranscriptionModelRole.class))
          .withUserConfiguration(TranscriptionController.class);

  @Test
  void theControllerIsRegisteredWithoutConfiguration() {
    runner.run(context -> assertThat(context).hasSingleBean(TranscriptionController.class));
  }

  @Test
  void theControllerIsNotRegisteredWhenTheFlagIsOff() {
    runner
        .withPropertyValues("opaa.features.speech-input.enabled=false")
        .run(
            context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(TranscriptionController.class));
  }
}
