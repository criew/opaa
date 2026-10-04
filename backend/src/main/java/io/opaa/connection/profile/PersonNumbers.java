package io.opaa.connection.profile;

import io.opaa.connection.profile.PersonConnections.PersonTotals;
import io.opaa.connection.profile.PersonConnections.StateCounts;
import io.opaa.permission.GroupSizeProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Component;

/**
 * The one way a number about persons' connections reaches the administration (ADR-0036,
 * Mindestgruppengröße N): the total of a profile is exact from N on, else "fewer than N" - zero
 * included, so no answer tells whether anyone is connected; a part (the expired) is exact only
 * where the total is and neither it nor its complement lies between 1 and N-1 - else "fewer than N"
 * if it is below N, else not told - so no subtraction of two answers points at a person. Numbers
 * per group of persons follow {@link #ofGroups}.
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

  /**
   * The connections and private libraries of each group of {@code personsByGroup} - disjoint, such
   * as the accounts of each sign-in provider - with {@code others} holding every remaining person.
   * The groups split the same totals the profiles tell, so a group number is never exact and never
   * "fewer than N": every group with persons is told "at least N" only if each of them, {@code
   * others} included, has at least N; else none is told anything ({@code null}). Whatever is known
   * of the totals, a group that is not told may then hold any number below N, zero included.
   */
  public <K> Map<K, PersonsCounts> ofGroups(
      Map<K, ? extends Collection<UUID>> personsByGroup, Collection<UUID> others) {
    Map<K, PersonTotals> raw = new HashMap<>();
    personsByGroup.forEach(
        (group, userIds) -> {
          if (!userIds.isEmpty()) {
            raw.put(group, persons.totalsOf(userIds));
          }
        });
    PersonTotals rest = others.isEmpty() ? null : persons.totalsOf(others);
    boolean connectionsTold = allReach(raw, rest, PersonTotals::connections);
    boolean librariesTold = allReach(raw, rest, PersonTotals::privateLibraries);
    Map<K, PersonsCounts> masked = new HashMap<>();
    for (K group : personsByGroup.keySet()) {
      boolean withPersons = raw.containsKey(group);
      masked.put(
          group,
          new PersonsCounts(
              withPersons && connectionsTold ? PersonCount.atLeast(minimum) : null,
              withPersons && librariesTold ? PersonCount.atLeast(minimum) : null));
    }
    return masked;
  }

  private <K> boolean allReach(
      Map<K, PersonTotals> groups, PersonTotals others, ToLongFunction<PersonTotals> number) {
    if (others != null && number.applyAsLong(others) < minimum) {
      return false;
    }
    return !groups.isEmpty()
        && groups.values().stream().allMatch(totals -> number.applyAsLong(totals) >= minimum);
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

  /**
   * The connections and the private libraries of a group of persons, each "at least N" or {@code
   * null} where it may not be told.
   */
  public record PersonsCounts(PersonCount connections, PersonCount privateLibraries) {}
}
