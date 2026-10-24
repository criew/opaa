package io.opaa.instance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the instance register (ADR-0053, Entscheidung 3), read with its liveness judged by the
 * database clock.
 *
 * @param id the identity of one process start; a restarted pod has a new one
 * @param hostName the host or pod name, for display only
 * @param startedAt when the instance registered, database clock
 * @param heartbeatAt its last heartbeat, database clock
 * @param stoppedAt when it signed off at an orderly stop; {@code null} while it has not
 * @param alive whether it signed off neither nor missed its heartbeat for longer than {@code
 *     opaa.instance.dead-after}
 */
public record RegisteredInstance(
    UUID id,
    String hostName,
    String version,
    Instant startedAt,
    Instant heartbeatAt,
    Instant stoppedAt,
    boolean alive) {

  public RegisteredInstance {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(heartbeatAt, "heartbeatAt");
  }
}
