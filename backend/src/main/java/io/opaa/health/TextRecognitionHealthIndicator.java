package io.opaa.health;

import io.opaa.llm.textrecognition.TextRecognitionRoleStatusProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The text recognition role in its own health group {@code text-recognition-model}. */
@Component
@ConditionalOnProperty(name = "management.health.textrecognition.enabled", matchIfMissing = true)
public class TextRecognitionHealthIndicator extends OptionalRoleHealthIndicator {

  public TextRecognitionHealthIndicator(TextRecognitionRoleStatusProvider role) {
    super(role, ModelHealthGroupsConfiguration.TEXT_RECOGNITION_GROUP);
  }
}
