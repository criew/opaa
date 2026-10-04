package io.opaa.connection.profile;

import io.opaa.connection.profile.PersonConnections.StateCounts;
import io.opaa.permission.GroupSizeProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one way a number about persons' connections reaches the administration (ADR-0036,
 * Mindestgruppengröße N): the total of a profile is exact at zero and from N on, else "fewer than
 * N"; a part (the expired) is exact only where neither it, its complement nor the total lies
 * between 1 and N-1 - else "fewer than N" if it is below N, else not told - so no subtraction of
 * two answers points at a person.
 */
@Component
public class PersonNumbers {

  private final PersonConnections persons;
  private final int minimum;

  PersonNumbers(PersonConnections persons, GroupSizeProperties groupSize) {
    this.persons = persons;
    this.minimum = groupSize.minimumGroupSize();
  }

  /** The masked numbers of each of {@code profileIds}, with one query for all. */
  public Map<UUID, ProfileCounts> countsOf(Collection<UUID> profileIds) {
    Map<UUID, StateCounts> raw = profileIds.isEmpty() ? Map.of() : persons.countsAmong(profileIds);
    Map<UUID, ProfileCounts> masked = new HashMap<>();
    for (UUID profileId : profileIds) {
      masked.put(profileId, mask(raw.getOrDefault(profileId, new StateCounts(0, 0))));
    }
    return masked;
  }

  /** The persons' connections on {@code profileId} that are not disconnected, masked. */
  public PersonCount totalOf(UUID profileId) {
    return countsOf(List.of(profileId)).get(profileId).total();
  }

  /** Whether any person is connected on {@code profileId}; tells no number. */
  public boolean anyOn(UUID profileId) {
    StateCounts counts = persons.countsAmong(List.of(profileId)).get(profileId);
    return counts != null && counts.connected() + counts.expired() > 0;
  }

  ProfileCounts mask(StateCounts counts) {
    long total = counts.connected() + counts.expired();
    PersonCount masked =
        revealing(total) ? PersonCount.fewerThan(minimum) : PersonCount.exact(total);
    boolean partReveals =
        revealing(total) || revealing(counts.connected()) || revealing(counts.expired());
    PersonCount expired;
    if (!partReveals) {
      expired = PersonCount.exact(counts.expired());
    } else if (counts.expired() < minimum) {
      expired = PersonCount.fewerThan(minimum);
    } else {
      expired = null;
    }
    return new ProfileCounts(masked, expired);
  }

  private boolean revealing(long count) {
    return count > 0 && count < minimum;
  }

  /**
   * The persons' connections on one profile and the expired among them; {@code expired} is {@code
   * null} where it may not be told at all.
   */
  public record ProfileCounts(PersonCount total, PersonCount expired) {}
}
