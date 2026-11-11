package io.opaa.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

  /** Result and time of the last probe as one snapshot, read without the lock. */
  private record Probed(Health health, Instant at) {}

  private volatile Probed last;

  CachedProbe(Supplier<Health> probe, Clock clock) {
    this.probe = probe;
    this.clock = clock;
  }

  /** The cached result, or a new probe once it has expired; one probe at a time. */
  synchronized Health health() {
    Instant now = clock.instant();
    Probed current = last;
    if (current == null || !now.isBefore(current.at().plus(TIME_TO_LIVE))) {
      current = new Probed(probe.get(), now);
      last = current;
    }
    return current.health();
  }

  /**
   * The last result if it is at most {@code maxAge} old; never probes and never waits for a probe
   * under way, so a reader is not held up by a slow model call.
   */
  Optional<Health> last(Duration maxAge) {
    Probed current = last;
    if (current == null || clock.instant().isAfter(current.at().plus(maxAge))) {
      return Optional.empty();
    }
    return Optional.of(current.health());
  }
}
