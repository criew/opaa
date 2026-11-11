package io.opaa.llm.transcription;

import io.opaa.llm.ModelRoleSettingsChangedEvent;
import io.opaa.llm.OptionalModelRole;
import io.opaa.llm.OptionalRoleStartupCheck;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

/**
 * The transcription role's own probe thread: an endpoint that accepts a connection but never
 * answers must not block the application's shared scheduler, which the indexing jobs use.
 */
@Configuration
@EnableConfigurationProperties(TranscriptionProperties.class)
public class TranscriptionRoleConfiguration {

  /** The role's own probe thread ({@link OptionalModelRole#probeScheduler}). */
  @Bean(destroyMethod = "shutdown")
  TaskScheduler transcriptionProbeScheduler() {
    return OptionalModelRole.probeScheduler("transcription-probe-");
  }

  @Bean
  OptionalRoleStartupCheck transcriptionRoleStartupCheck(
      TranscriptionModelRole role,
      @Qualifier("transcriptionProbeScheduler") TaskScheduler probeScheduler,
      Clock clock) {
    return new OptionalRoleStartupCheck(
        role,
        ModelRoleSettingsChangedEvent.Role.TRANSCRIPTION,
        "Transcription",
        "Dictation stays hidden until the endpoint answers.",
        probeScheduler,
        clock);
  }
}
