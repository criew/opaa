package io.opaa.llm;

import io.opaa.api.types.ToolCapability;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warns at start when the active chat model's tool capability is {@link ToolCapability#UNVERIFIED}:
 * turns that need tool calls are refused until the Systemverwaltung records it. Only warns and
 * never refuses the start - the ordinary chat works with any model. Each instance warns once for
 * itself (ADR-0053); a failing read is logged, not thrown.
 */
@Component
class ToolCapabilityStartupWarning {

  private static final Logger log = LoggerFactory.getLogger(ToolCapabilityStartupWarning.class);

  private final LlmModelRepository repository;

  ToolCapabilityStartupWarning(LlmModelRepository repository) {
    this.repository = repository;
  }

  @EventListener(ApplicationReadyEvent.class)
  void warnAboutAnUnverifiedActiveModel() {
    try {
      warning(repository.findAllByActiveTrue()).ifPresent(log::warn);
    } catch (RuntimeException e) {
      log.warn("Could not read the tool capability of the active chat model at start", e);
    }
  }

  /** The warning for the active models, empty when none is active or none is unverified. */
  static Optional<String> warning(List<LlmModel> activeModels) {
    return activeModels.stream()
        .filter(model -> model.getToolCapability() == ToolCapability.UNVERIFIED)
        .findFirst()
        .map(
            model ->
                "The tool capability of the active chat model '"
                    + model.getModelIdentifier()
                    + "' at "
                    + ModelEndpointUri.withoutSecrets(model.getBaseUrl())
                    + " is UNVERIFIED: turns that need tool calls are refused until a"
                    + " SYSTEM_ADMIN records it in the model administration. The ordinary chat"
                    + " is not affected.");
  }
}
