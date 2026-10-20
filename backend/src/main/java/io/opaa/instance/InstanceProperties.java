package io.opaa.instance;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The heartbeat of the instance register (ADR-0053, Entscheidung 3).
 *
 * @param heartbeatInterval how often this instance writes its heartbeat; at most a quarter of
 *     {@code deadAfter}
 * @param deadAfter how long an instance without heartbeat is still considered alive; a dead
 *     instance's configuration no longer binds a starting one, and its runs may be taken over
 */
@ConfigurationProperties(prefix = "opaa.instance")
public record InstanceProperties(
    @NoDataImpact("how often this process reports that it is alive")
        @PerInstance("each instance writes its own heartbeat")
        Duration heartbeatInterval,
    @NoDataImpact("when a silent process counts as gone") @InstallationWide Duration deadAfter) {

  /** The deadline covers at least this many missed heartbeats. */
  static final int MIN_MISSED_HEARTBEATS = 4;

  public InstanceProperties {
    Objects.requireNonNull(heartbeatInterval, "opaa.instance.heartbeat-interval");
    Objects.requireNonNull(deadAfter, "opaa.instance.dead-after");
    if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
      throw new IllegalArgumentException(
          "opaa.instance.heartbeat-interval must be positive, is " + heartbeatInterval);
    }
    if (deadAfter.compareTo(heartbeatInterval.multipliedBy(MIN_MISSED_HEARTBEATS)) < 0) {
      throw new IllegalArgumentException(
          "opaa.instance.dead-after ("
              + deadAfter
              + ") must be at least "
              + MIN_MISSED_HEARTBEATS
              + " times opaa.instance.heartbeat-interval ("
              + heartbeatInterval
              + ")");
    }
  }
}
