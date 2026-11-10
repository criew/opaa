package io.opaa.observability;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.health.contributor.Status;

/**
 * A health group whose probe costs a call to a model: {@link HealthGroupMetricsConfiguration} reads
 * its last result instead of probing, so the metric never causes a model call of its own.
 */
public interface PassiveHealthGroup {

  /** The name of the health group. */
  String healthGroup();

  /** The status of the last probe if it is at most {@code maxAge} old, without probing. */
  Optional<Status> lastStatus(Duration maxAge);
}
