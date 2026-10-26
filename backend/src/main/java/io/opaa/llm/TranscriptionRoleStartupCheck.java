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
 * Probes the transcription role once the application is up and after every change in the model
 * administration, off the event thread, and logs a switched-on but unusable role at {@code ERROR}.
 * The application starts either way.
 */
@Component
class TranscriptionRoleStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(TranscriptionRoleStartupCheck.class);

  private final TranscriptionModelRole role;
  private final TaskScheduler probeScheduler;
  private final Clock clock;

  TranscriptionRoleStartupCheck(
      TranscriptionModelRole role,
      @Qualifier("transcriptionProbeScheduler") TaskScheduler probeScheduler,
      Clock clock) {
    this.role = role;
    this.probeScheduler = probeScheduler;
    this.clock = clock;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
  }

  /** A role changed in the model administration is probed at once, off the request thread. */
  @EventListener
  void onSettingsChanged(ModelRoleSettingsChangedEvent event) {
    if (event.role() == ModelRoleSettingsChangedEvent.Role.TRANSCRIPTION) {
      probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
    }
  }

  void probeAndReport() {
    TranscriptionRoleStatus status = role.refresh();
    if (status.state() == TranscriptionRoleState.UNCONFIGURED
        || status.state() == TranscriptionRoleState.UNREACHABLE) {
      log.error(
          "Transcription role is switched on but not usable (state {}{}): {}. Endpoint: {}."
              + " Dictation stays hidden until the endpoint answers.",
          status.state(),
          status.timedOut() ? ", timed out rather than unreachable" : "",
          status.diagnostic(),
          endpointForLog(status));
      return;
    }
    log.info("Transcription role state: {}", status.state());
  }

  private static String endpointForLog(TranscriptionRoleStatus status) {
    if (status.baseUrlRejected()) {
      return "(rejected: the configured address carries credentials)";
    }
    return status.baseUrl() == null ? "(none configured)" : status.baseUrl();
  }
}
