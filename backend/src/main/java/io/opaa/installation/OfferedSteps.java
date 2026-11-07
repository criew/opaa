package io.opaa.installation;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.llm.TextRecognitionModelRole;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which steps this installation offers (ADR-0060, Entscheidungen 16 and 18): a step whose limit of
 * the operation is off is neither listed nor saved, checked, skipped or completed, and its part of
 * an answer is ignored. Both limits are read at start-up only.
 */
@Component
class OfferedSteps {

  private final boolean speechInput;
  private final boolean textRecognition;

  @Autowired
  OfferedSteps(FeatureFlags features, TextRecognitionModelRole textRecognitionRole) {
    this(features, textRecognitionRole.operationEnabled());
  }

  /** {@code textRecognition} is the operation's switch {@code opaa.ocr.enabled}. */
  OfferedSteps(FeatureFlags features, boolean textRecognition) {
    this.speechInput = features.isEnabled(Feature.SPEECH_INPUT);
    this.textRecognition = textRecognition;
  }

  boolean isOffered(InstallationStep step) {
    return switch (step.limit()) {
      case null -> true;
      case SPEECH_INPUT -> speechInput;
      case TEXT_RECOGNITION -> textRecognition;
    };
  }

  /** The steps offered, in their order. */
  List<InstallationStep> steps() {
    return Arrays.stream(InstallationStep.values()).filter(this::isOffered).toList();
  }

  /** The German reason why {@code step} is not offered, naming the switch of the operation. */
  static String notOffered(InstallationStep step) {
    String reason =
        step.limit() == InstallationStep.Limit.TEXT_RECOGNITION
            ? "Die Texterkennung ist im Betrieb nicht freigegeben (OPAA_OCR_ENABLED)."
            : "Die Spracheingabe ist im Betrieb abgeschaltet (OPAA_FEATURES_SPEECH_INPUT_ENABLED).";
    return "Den Schritt " + step.name() + " gibt es in dieser Installation nicht: " + reason;
  }
}
