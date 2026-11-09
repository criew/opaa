package io.opaa.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;

/**
 * The last result of a health probe that costs a call to a foreign service, handed out again for
 * {@link #TIME_TO_LIVE}: repeated or concurrent calls of the group cause one model call, not one
 * each. The result is held per instance (ADR-0053); after the time it is probed anew.
 */
final class CachedProbe {

  /** How long a probe result is handed out again. */
  static final Duration TIME_TO_LIVE = Duration.ofSeconds(30);

  private final Supplier<Health> probe;
  private final Clock clock;

  private Health last;
  private Instant probedAt;

  CachedProbe(Supplier<Health> probe, Clock clock) {
    this.probe = probe;
    this.clock = clock;
  }

  /** The cached result, or a new probe once it has expired; one probe at a time. */
  synchronized Health health() {
    Instant now = clock.instant();
    if (last == null || !now.isBefore(probedAt.plus(TIME_TO_LIVE))) {
      last = probe.get();
      probedAt = now;
    }
    return last;
  }
}
