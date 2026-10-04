package io.opaa.connection.profile;

import io.opaa.connection.profile.PersonConnections.StateCounts;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import io.opaa.permission.GroupSizeProperties;
import java.util.Map;

/** Masked person counts for tests of the web layer, under the delivered minimum group size 5. */
public final class TestPersonCounts {

  private static final PersonNumbers NUMBERS =
      new PersonNumbers(new FixedCounts(), new GroupSizeProperties(5));

  private TestPersonCounts() {}

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
