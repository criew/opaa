package io.opaa.connection.profile;

import io.opaa.connection.profile.PersonConnections.StateCounts;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.PersonThreshold;
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
 * if it is below N, else not told - so no subtraction of two answers points at a person.
 */
@Component
public class PersonNumbers {

  private final PersonConnections persons;
  private final PersonThreshold threshold;
  private final int minimum;

  PersonNumbers(PersonConnections persons, GroupSizeProperties groupSize) {
    this.persons = persons;
    this.threshold = new PersonThreshold(groupSize);
    this.minimum = threshold.minimum();
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

  private PersonCount total(long count) {
    return threshold.discloses(count) ? PersonCount.exact(count) : PersonCount.fewerThan(minimum);
  }

  /**
   * A number of private libraries as the administration may see it, a part of the organization's:
   * it rests on their {@code owners}, the rest of the organization's on {@code otherOwners}, the
   * owners of every other private library. Exact only where {@link PersonThreshold#disclosesPart}
   * allows; else "fewer than N" below N owners - zero included - and not told ({@code null}) from N
   * on.
   */
  public PersonCount privateLibraries(long libraries, long owners, long otherOwners) {
    if (threshold.disclosesPart(owners, otherOwners)) {
      return PersonCount.exact(libraries);
    }
    return owners < minimum ? PersonCount.fewerThan(minimum) : null;
  }

  ProfileCounts mask(StateCounts counts) {
    long total = counts.connected() + counts.expired();
    boolean totalMasked = total < minimum;
    PersonCount masked = totalMasked ? PersonCount.fewerThan(minimum) : PersonCount.exact(total);
    boolean partReveals =
        totalMasked || revealing(counts.connected()) || revealing(counts.expired());
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
