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
 * included, so no answer tells whether anyone is connected; the expired part is exact only where
 * {@link PersonThreshold#disclosesPart} allows against the connected, else "fewer than N" if it is
 * below N and not told from N on - and not told at all on a total below 2N-1 - so on one total none
 * answers like a few on either side. The warning on the expired follows only from what is told: the
 * least number of expired the masked answer admits reaches the threshold.
 */
@Component
public class PersonNumbers {

  private final PersonConnections persons;
  private final PersonThreshold threshold;
  private final int minimum;
  private final int warningThreshold;

  PersonNumbers(
      PersonConnections persons,
      GroupSizeProperties groupSize,
      ExpiredConnectionWarningProperties warning) {
    this.persons = persons;
    this.threshold = new PersonThreshold(groupSize);
    this.minimum = threshold.minimum();
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

  private PersonCount total(long count) {
    return threshold.discloses(count) ? PersonCount.exact(count) : PersonCount.fewerThan(minimum);
  }

  /**
   * A number of private libraries as the administration may see it, a part of the private libraries
   * of each organization it touches: exact only where {@link PersonThreshold#disclosesPart} allows
   * in every one of them; else "fewer than N" below N owners in all - zero included - and not told
   * ({@code null}) from N on.
   */
  public PersonCount privateLibraries(long libraries, Collection<OwnerSplit> organizations) {
    boolean exact = true;
    long owners = 0;
    for (OwnerSplit organization : organizations) {
      exact &= threshold.disclosesPart(organization.owners(), organization.otherOwners());
      owners += organization.owners();
    }
    if (exact && !organizations.isEmpty()) {
      return PersonCount.exact(libraries);
    }
    return owners < minimum ? PersonCount.fewerThan(minimum) : null;
  }

  ProfileCounts mask(StateCounts counts) {
    long connected = counts.connected();
    long expired = counts.expired();
    long total = connected + expired;
    if (!threshold.discloses(total)) {
      return new ProfileCounts(
          PersonCount.fewerThan(minimum), PersonCount.fewerThan(minimum), false);
    }
    PersonCount told = PersonCount.exact(total);
    if (threshold.disclosesPart(expired, connected)) {
      return new ProfileCounts(told, PersonCount.exact(expired), expired >= warningThreshold);
    }
    if (total < 2L * minimum - 1) {
      // a few expired and a few connected overlap on this total, so any answer would split them
      return new ProfileCounts(told, null, false);
    }
    if (expired < minimum) {
      return new ProfileCounts(told, PersonCount.fewerThan(minimum), false);
    }
    // untold: the connected are 0 to N-1, so the expired are at least total - (N-1)
    return new ProfileCounts(told, null, total - (minimum - 1) >= warningThreshold);
  }

  /**
   * The persons' connections on one profile and the expired among them; {@code expired} is {@code
   * null} where it may not be told at all. {@code expiredWarning}: the told numbers show at least
   * the warning threshold of expired.
   */
  public record ProfileCounts(PersonCount total, PersonCount expired, boolean expiredWarning) {}

  /**
   * One organization's share of a number of private libraries: the persons owning the counted ones,
   * and the persons owning every other private library of the organization.
   */
  public record OwnerSplit(long owners, long otherOwners) {}
}
