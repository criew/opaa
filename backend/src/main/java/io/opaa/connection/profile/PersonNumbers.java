package io.opaa.connection.profile;

import io.opaa.connection.profile.PersonConnections.PersonTotals;
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
 * Mindestgruppengröße N): the total of a profile is exact from N on, else "fewer than N" - zero
 * included, so no answer tells whether anyone is connected; a part (the expired) is exact only
 * where the total is and neither it nor its complement lies between 1 and N-1 - else "fewer than N"
 * if it is below N, else not told - so no subtraction of two answers points at a person. The
 * warning on the expired follows only from what is told: the least number of expired the masked
 * answer admits reaches the threshold.
 */
@Component
public class PersonNumbers {

  private final PersonConnections persons;
  private final int minimum;
  private final int warningThreshold;

  PersonNumbers(
      PersonConnections persons,
      GroupSizeProperties groupSize,
      ExpiredConnectionWarningProperties warning) {
    this.persons = persons;
    this.minimum = groupSize.minimumGroupSize();
    this.warningThreshold = warning.warningThreshold();
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

  /**
   * The connections and private libraries of {@code userIds} as a whole, each masked like a total;
   * neither tells whether one of them has any.
   */
  public PersonsCounts ofPersons(Collection<UUID> userIds) {
    PersonTotals raw = userIds.isEmpty() ? new PersonTotals(0, 0) : persons.totalsOf(userIds);
    return new PersonsCounts(total(raw.connections()), total(raw.privateLibraries()));
  }

  private PersonCount total(long count) {
    return count < minimum ? PersonCount.fewerThan(minimum) : PersonCount.exact(count);
  }

  ProfileCounts mask(StateCounts counts) {
    long total = counts.connected() + counts.expired();
    boolean totalMasked = total < minimum;
    PersonCount masked = totalMasked ? PersonCount.fewerThan(minimum) : PersonCount.exact(total);
    boolean partReveals =
        totalMasked || revealing(counts.connected()) || revealing(counts.expired());
    PersonCount expired;
    long leastExpired;
    if (!partReveals) {
      expired = PersonCount.exact(counts.expired());
      leastExpired = counts.expired();
    } else if (counts.expired() < minimum) {
      expired = PersonCount.fewerThan(minimum);
      leastExpired = 0;
    } else {
      // untold: the total is exact and the connected lie between 1 and N-1, so the expired are at
      // least N and at least total - (N-1) - both known from the answer and the rule
      expired = null;
      leastExpired = Math.max(minimum, total - (minimum - 1));
    }
    return new ProfileCounts(masked, expired, leastExpired >= warningThreshold);
  }

  private boolean revealing(long count) {
    return count > 0 && count < minimum;
  }

  /**
   * The persons' connections on one profile and the expired among them; {@code expired} is {@code
   * null} where it may not be told at all. {@code expiredWarning}: the told numbers show at least
   * the warning threshold of expired.
   */
  public record ProfileCounts(PersonCount total, PersonCount expired, boolean expiredWarning) {}

  /** The connections and the private libraries of a set of persons, both masked. */
  public record PersonsCounts(PersonCount connections, PersonCount privateLibraries) {}
}
