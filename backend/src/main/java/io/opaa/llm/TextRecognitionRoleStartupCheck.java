package io.opaa.llm;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Probes the text recognition role once the application is up and after every change in the model
 * administration, off the event thread, and logs a switched-on but unusable role at {@code ERROR}.
 * Contacts nothing while the operation's switch is off; the application starts either way.
 */
@Component
class TextRecognitionRoleStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(TextRecognitionRoleStartupCheck.class);

  private final TextRecognitionModelRole role;
  private final TaskScheduler probeScheduler;
  private final Clock clock;

  TextRecognitionRoleStartupCheck(
      TextRecognitionModelRole role,
      @Qualifier("textRecognitionProbeScheduler") TaskScheduler probeScheduler,
      Clock clock) {
    this.role = role;
    this.probeScheduler = probeScheduler;
    this.clock = clock;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (role.operationEnabled()) {
      probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
    }
  }

  /** A role changed in the model administration is probed at once, off the request thread. */
  @EventListener
  void onSettingsChanged(ModelRoleSettingsChangedEvent event) {
    if (event.role() == ModelRoleSettingsChangedEvent.Role.TEXT_RECOGNITION
        && role.operationEnabled()) {
      probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
    }
  }

  void probeAndReport() {
    TextRecognitionRoleStatus status = role.refresh();
    if (status.state() == TextRecognitionRoleState.UNCONFIGURED
        || status.state() == TextRecognitionRoleState.UNREACHABLE) {
      log.error(
          "Text recognition role is switched on but not usable (state {}{}): {}. Endpoint: {}."
              + " Scanned PDFs stay rejected until the service answers.",
          status.state(),
          status.timedOut() ? ", timed out rather than unreachable" : "",
          status.diagnostic(),
          status.baseUrlRejected()
              ? "(rejected: the configured address carries credentials)"
              : status.baseUrl() == null ? "(none configured)" : status.baseUrl());
      return;
    }
    log.info("Text recognition role state: {}", status.state());
  }
}
