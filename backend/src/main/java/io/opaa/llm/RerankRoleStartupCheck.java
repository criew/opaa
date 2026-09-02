package io.opaa.llm;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Probes the rerank role once the application is up and reports the result
 * (docs/features/hybrid-retrieval.md, Arbeitspaket 4): a contradiction between the switch and the
 * role's configuration is logged at {@code ERROR}, so it cannot pass for a normal startup line.
 *
 * <p><b>The probe runs off the event thread</b>, on the role's own probe scheduler: it opens a
 * network connection, and an endpoint that accepts a connection but never answers would otherwise
 * hold up {@code ApplicationReadyEvent} for the connect timeout plus {@code OPAA_RERANK_TIMEOUT}
 * and delay every later listener with it.
 *
 * <p><b>The application still starts.</b> A misconfigured rerank role degrades retrieval, it does
 * not break it - failing the start would take a whole installation offline over an optional
 * component. The startup line is therefore only half the answer; the other half is {@link
 * RerankModelRole#currentStatus()}, which stays readable long after this line has scrolled away.
 */
@Component
class RerankRoleStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(RerankRoleStartupCheck.class);

  private final RerankModelRole role;
  private final TaskScheduler probeScheduler;

  RerankRoleStartupCheck(
      RerankModelRole role, @Qualifier("rerankProbeScheduler") TaskScheduler probeScheduler) {
    this.role = role;
    this.probeScheduler = probeScheduler;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    probeScheduler.schedule(this::probeAndReport, Instant.now());
  }

  void probeAndReport() {
    RerankRoleStatus status = role.refresh();
    boolean contradictsIntent =
        status.state() == RerankRoleState.UNCONFIGURED
            || status.state() == RerankRoleState.UNREACHABLE;
    if (contradictsIntent) {
      log.error(
          "Rerank role is switched on but not usable (state {}): {}. Endpoint: {}. Retrieval "
              + "continues without reranking; the state stays readable via "
              + "RerankRoleStatusProvider#currentStatus.",
          status.state(),
          status.diagnostic(),
          status.baseUrl() == null ? "(none configured)" : status.baseUrl());
      return;
    }
    log.info("Rerank role state: {}", status.state());
  }
}
