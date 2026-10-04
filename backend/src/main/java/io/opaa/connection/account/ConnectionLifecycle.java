package io.opaa.connection.account;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the lifecycle of persons' connections as {@link ConnectionLifecycleReconciler} last
 * recorded it. Only deactivation starts the deletion period of private libraries; a resting person
 * is never reported here.
 */
@Service
@Transactional(readOnly = true)
public class ConnectionLifecycle {

  private final ConnectionPersonStateRepository states;

  ConnectionLifecycle(ConnectionPersonStateRepository states) {
    this.states = states;
  }

  /** Since when person {@code userId} is deactivated, empty while they are not. */
  public Optional<Instant> deactivatedSince(UUID userId) {
    return states.findById(userId).map(ConnectionPersonState::getDeactivatedSince);
  }

  /** The persons deactivated since before {@code before}, with the start of their deactivation. */
  public Map<UUID, Instant> deactivatedBefore(Instant before) {
    Map<UUID, Instant> found = new LinkedHashMap<>();
    for (ConnectionPersonState state : states.findDeactivatedBefore(before)) {
      found.put(state.getUserId(), state.getDeactivatedSince());
    }
    return found;
  }
}
