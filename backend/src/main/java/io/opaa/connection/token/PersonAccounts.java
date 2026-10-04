package io.opaa.connection.token;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The port naming the connected account a person holds on a profile, which owns a person's secret
 * in the store; the account package answers it.
 */
public interface PersonAccounts {

  /** The accounts of any of {@code userIds} on any of {@code profileIds}, in one query. */
  List<AccountKey> accountsAmong(Collection<UUID> userIds, Collection<UUID> profileIds);

  /** One connected account by the person and profile it joins. */
  interface AccountKey {
    UUID getId();

    UUID getUserId();

    UUID getProfileId();
  }
}
