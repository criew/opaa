package io.opaa.succession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The schedule of the detection run is read from {@code OPAA_SUCCESSION_DETECTION_CRON}: an unset
 * value takes the hourly default, a blank or malformed one refuses the start with a message that
 * names the variable instead of an error from deep inside the scheduling machinery.
 */
class SuccessionSchedulerCronTest {

  private static final String DEFAULT_CRON = "0 5 * * * *";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              SchedulingEnabled.class, SuccessionConfiguration.class, SuccessionScheduler.class)
          .withBean(SuccessionDetectionService.class, () -> mock(SuccessionDetectionService.class))
          .withBean(SuccessionRetentionService.class, () -> mock(SuccessionRetentionService.class));

  @Test
  void anUnsetValueSchedulesTheDetectionHourly() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(detectionCron(context.getBean(ScheduledTaskHolder.class)))
              .isEqualTo(DEFAULT_CRON);
        });
  }

  @Test
  void aConfiguredValueSchedulesTheDetection() {
    contextRunner
        .withPropertyValues("opaa.succession.detection-cron=0 15 */2 * * *")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(detectionCron(context.getBean(ScheduledTaskHolder.class)))
                  .isEqualTo("0 15 */2 * * *");
            });
  }

  // regression guard for #3397: an empty variable in a .env file must not end in a scheduler error
  @Test
  void aBlankValueRefusesTheStartAndNamesTheVariable() {
    contextRunner
        .withPropertyValues("opaa.succession.detection-cron=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_SUCCESSION_DETECTION_CRON")
                    .hasMessageContaining("empty"));
  }

  @Test
  void aMalformedValueRefusesTheStartAndNamesTheVariable() {
    contextRunner
        .withPropertyValues("opaa.succession.detection-cron=stündlich")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_SUCCESSION_DETECTION_CRON")
                    .hasMessageContaining("stündlich"));
  }

  private static String detectionCron(ScheduledTaskHolder holder) {
    return holder.getScheduledTasks().stream()
        .map(scheduled -> scheduled.getTask())
        .filter(CronTask.class::isInstance)
        .map(CronTask.class::cast)
        .filter(task -> task.toString().contains("detectOpenSuccessions"))
        .map(CronTask::getExpression)
        .findFirst()
        .orElseThrow();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingEnabled {}
}
