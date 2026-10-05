package io.opaa.connection.account;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.AccountUsability.Deactivation;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.profile.DeactivationStarts;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the lifecycle of persons' connections as {@link ConnectionLifecycleReconciler} last
 * recorded it. Only deactivation starts the deletion period of private libraries, and only one by
 * an act that ends the account, as it holds now: its period runs from the later of the recorded
 * start and that act, so a lock renewed after a reactivation starts it anew. A resting person - a
 * local account locked for inactivity among them - has none.
 */
@Service
@Transactional(readOnly = true)
public class ConnectionLifecycle implements DeactivationStarts {

  private final ConnectionPersonStateRepository states;
  private final UserRepository users;
  private final AccountUsability usability;

  ConnectionLifecycle(
      ConnectionPersonStateRepository states, UserRepository users, AccountUsability usability) {
    this.states = states;
    this.users = users;
    this.usability = usability;
  }

  /** Since when person {@code userId} is deactivated as recorded, empty while they are not. */
  public Optional<Instant> deactivatedSince(UUID userId) {
    return states.findById(userId).map(ConnectionPersonState::getDeactivatedSince);
  }

  /** The persons recorded as deactivated since before {@code before}, with the recorded start. */
  public Map<UUID, Instant> deactivatedBefore(Instant before) {
    Map<UUID, Instant> found = new LinkedHashMap<>();
    for (ConnectionPersonState state : states.findDeactivatedBefore(before)) {
      found.put(state.getUserId(), state.getDeactivatedSince());
    }
    return found;
  }

  /** The start of the deletion period of each of {@code userIds} that has one running now. */
  @Override
  public Map<UUID, Instant> deactivatedSince(Collection<UUID> userIds) {
    return periodStarts(states.findAllById(userIds));
  }

  /** The persons whose deletion period started before {@code before}, with its start. */
  public Map<UUID, Instant> deletionPeriodStartedBefore(Instant before) {
    Map<UUID, Instant> found = new LinkedHashMap<>();
    periodStarts(states.findDeactivatedBefore(before))
        .forEach(
            (userId, start) -> {
              if (start.isBefore(before)) {
                found.put(userId, start);
              }
            });
    return found;
  }

  private Map<UUID, Instant> periodStarts(List<ConnectionPersonState> recorded) {
    Map<UUID, Instant> recordedStart = new LinkedHashMap<>();
    for (ConnectionPersonState state : recorded) {
      if (state.getDeactivatedSince() != null) {
        recordedStart.put(state.getUserId(), state.getDeactivatedSince());
      }
    }
    if (recordedStart.isEmpty()) {
      return Map.of();
    }
    List<User> found = users.findAllById(recordedStart.keySet());
    Map<UUID, Deactivation> current = usability.snapshot().deactivationsOf(found);
    Map<UUID, Instant> starts = new LinkedHashMap<>();
    current.forEach(
        (userId, deactivation) -> {
          Instant start = recordedStart.get(userId);
          if (deactivation.since() != null && deactivation.since().isAfter(start)) {
            start = deactivation.since();
          }
          starts.put(userId, start);
        });
    return starts;
  }
}
