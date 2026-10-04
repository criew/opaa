package io.opaa.connection.profile;

import io.opaa.connection.profile.PersonConnections.StateCounts;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import io.opaa.permission.GroupSizeProperties;
import java.util.Map;

/** Masked person counts for tests of the web layer, under the delivered minimum group size 5. */
public final class TestPersonCounts {

  /** A profile no person is connected on. */
  public static final PersonConnections NO_PERSONS = new FixedCounts();

  private static final PersonNumbers NUMBERS =
      new PersonNumbers(NO_PERSONS, new GroupSizeProperties(5));

  private TestPersonCounts() {}

  /** The masking over {@link #NO_PERSONS}. */
  public static PersonNumbers numbers() {
    return NUMBERS;
  }

  public static ProfileCounts of(long connected, long expired) {
    return NUMBERS.mask(new StateCounts(connected, expired));
  }

  private static final class FixedCounts implements PersonConnections {
    @Override
    public Map<java.util.UUID, StateCounts> countsAmong(
        java.util.Collection<java.util.UUID> profileIds) {
      return Map.of();
    }

    @Override
    public void endAllUnder(
        java.util.UUID profileId,
        io.opaa.api.types.ConnectionEndCause cause,
        java.util.UUID actorUserId) {}
  }
}
