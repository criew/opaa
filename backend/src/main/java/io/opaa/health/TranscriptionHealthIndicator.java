package io.opaa.health;

import io.opaa.llm.transcription.TranscriptionRoleStatusProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The transcription role in its own health group {@code transcription-model}. */
@Component
@ConditionalOnProperty(name = "management.health.transcription.enabled", matchIfMissing = true)
public class TranscriptionHealthIndicator extends OptionalRoleHealthIndicator {

  public TranscriptionHealthIndicator(TranscriptionRoleStatusProvider role) {
    super(role, ModelHealthGroupsConfiguration.TRANSCRIPTION_GROUP);
  }
}
