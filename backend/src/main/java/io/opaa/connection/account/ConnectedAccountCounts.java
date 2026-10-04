package io.opaa.connection.account;

import io.opaa.api.types.ConnectedAccountState;
import io.opaa.permission.GroupSizeProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only view of connected accounts the administration gets: numbers per profile, each a {@link
 * PersonCount} below the minimum group size. It names no person and no account.
 */
@Service
@Transactional(readOnly = true)
public class ConnectedAccountCounts {

  private final ConnectedAccountRepository accounts;
  private final int minimumGroupSize;

  ConnectedAccountCounts(ConnectedAccountRepository accounts, GroupSizeProperties groupSize) {
    this.accounts = accounts;
    this.minimumGroupSize = groupSize.minimumGroupSize();
  }

  /** Connected and expired accounts of each of {@code profileIds}, with one query for all. */
  public Map<UUID, ProfileCounts> countsOf(Collection<UUID> profileIds) {
    Map<UUID, long[]> raw = new HashMap<>();
    if (!profileIds.isEmpty()) {
      for (ConnectedAccountRepository.StateCount row :
          accounts.countByProfileAndState(profileIds)) {
        long[] counts = raw.computeIfAbsent(row.getProfileId(), id -> new long[2]);
        if (row.getState() == ConnectedAccountState.CONNECTED) {
          counts[0] += row.getConnections();
        } else if (row.getState() == ConnectedAccountState.EXPIRED) {
          counts[1] += row.getConnections();
        }
      }
    }
    Map<UUID, ProfileCounts> result = new HashMap<>();
    for (UUID profileId : profileIds) {
      long[] counts = raw.getOrDefault(profileId, new long[2]);
      result.put(profileId, new ProfileCounts(masked(counts[0]), masked(counts[1])));
    }
    return result;
  }

  /** {@code count} persons' connections as the administration may see it. */
  public PersonCount masked(long count) {
    return PersonCount.of(count, minimumGroupSize);
  }

  /** The connected and the expired accounts of one profile. */
  public record ProfileCounts(PersonCount connected, PersonCount expired) {}
}
