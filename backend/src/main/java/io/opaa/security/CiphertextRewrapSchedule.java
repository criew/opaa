package io.opaa.security;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The tick of {@link CiphertextRewrap}: every ten minutes, on one instance at a time under its
 * ShedLock lock (ADR-0053). Switched off with {@code
 * opaa.security.ciphertext-rewrap.schedule-enabled=false}, where a tick would rewrite a value a
 * test compares as stored; the rewrap itself is then called directly.
 */
@Component
@ConditionalOnProperty(
    prefix = "opaa.security.ciphertext-rewrap",
    name = "schedule-enabled",
    matchIfMissing = true)
class CiphertextRewrapSchedule {

  private final CiphertextRewrap rewrap;

  CiphertextRewrapSchedule(CiphertextRewrap rewrap) {
    this.rewrap = rewrap;
  }

  @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
  @SchedulerLock(name = "ciphertext-rewrap", lockAtLeastFor = "PT9M", lockAtMostFor = "PT30M")
  public void tick() {
    rewrap.runAndLog();
  }
}
