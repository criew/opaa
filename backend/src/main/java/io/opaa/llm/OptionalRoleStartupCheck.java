package io.opaa.llm;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

/**
 * Probes an optional model role once the application is up and after every change of it in the
 * model administration, and reports the result (ADR-0065, Entscheidung 6): a switched-on role that
 * is not usable is logged at {@code ERROR}, so it cannot pass for a normal startup line. Contacts
 * nothing while the operation does not allow the role.
 *
 * <p><b>The probe runs off the event thread</b>, on the role's own probe scheduler: an endpoint
 * that accepts a connection but never answers would otherwise hold up {@code ApplicationReadyEvent}
 * and every later listener. <b>The application still starts</b>; the other half of the answer is
 * {@link OptionalModelRole#currentStatus()}, which stays readable after this line has scrolled
 * away.
 */
class OptionalRoleStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(OptionalRoleStartupCheck.class);

  private final OptionalModelRole<?> role;
  private final ModelRoleSettingsChangedEvent.Role changes;
  private final String name;
  private final String consequence;
  private final TaskScheduler probeScheduler;
  private final Clock clock;

  /**
   * @param name the role at the start of a log line, e.g. {@code "Rerank"}
   * @param consequence what the application does while the role is unusable, one sentence
   */
  OptionalRoleStartupCheck(
      OptionalModelRole<?> role,
      ModelRoleSettingsChangedEvent.Role changes,
      String name,
      String consequence,
      TaskScheduler probeScheduler,
      Clock clock) {
    this.role = role;
    this.changes = changes;
    this.name = name;
    this.consequence = consequence;
    this.probeScheduler = probeScheduler;
    this.clock = clock;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (role.operationAllows()) {
      probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
    }
  }

  /** A role changed in the model administration is probed at once, off the request thread. */
  @EventListener
  void onSettingsChanged(ModelRoleSettingsChangedEvent event) {
    if (event.role() == changes && role.operationAllows()) {
      probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
    }
  }

  void probeAndReport() {
    OptionalRoleStatus status = role.refresh();
    if (status.state() == OptionalRoleState.UNCONFIGURED
        || status.state() == OptionalRoleState.UNREACHABLE) {
      log.error(
          "{} role is switched on but not usable (state {}{}): {}. Endpoint: {}. {}",
          name,
          status.state(),
          // A timeout means the endpoint accepted the connection but did not answer in time: a
          // tuning question, not evidence the endpoint is down.
          status.timedOut() ? ", timed out rather than unreachable" : "",
          status.diagnostic(),
          endpointForLog(status),
          consequence);
      return;
    }
    log.info("{} role state: {}", name, status.state());
  }

  /**
   * The address as far as it may be written to a log file: an address refused for carrying
   * credentials is named as refused, never reproduced.
   */
  private static String endpointForLog(OptionalRoleStatus status) {
    if (status.baseUrlRejected()) {
      return "(rejected: the configured address carries credentials)";
    }
    return status.baseUrl() == null ? "(none configured)" : status.baseUrl();
  }
}
