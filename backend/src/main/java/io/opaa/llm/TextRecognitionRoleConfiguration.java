package io.opaa.llm;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

/**
 * The operation's switch and limits of text recognition ({@link TextRecognitionProperties}) and the
 * role's own probe thread: a service that accepts a connection but never answers must not block the
 * application's shared scheduler.
 */
@Configuration
@EnableConfigurationProperties(TextRecognitionProperties.class)
public class TextRecognitionRoleConfiguration {

  /** The role's own probe thread ({@link OptionalModelRole#probeScheduler}). */
  @Bean(destroyMethod = "shutdown")
  TaskScheduler textRecognitionProbeScheduler() {
    return OptionalModelRole.probeScheduler("text-recognition-probe-");
  }

  @Bean
  OptionalRoleStartupCheck textRecognitionRoleStartupCheck(
      TextRecognitionModelRole role,
      @Qualifier("textRecognitionProbeScheduler") TaskScheduler probeScheduler,
      Clock clock) {
    return new OptionalRoleStartupCheck(
        role,
        ModelRoleSettingsChangedEvent.Role.TEXT_RECOGNITION,
        "Text recognition",
        "Scanned PDFs stay rejected until the service answers.",
        probeScheduler,
        clock);
  }
}
