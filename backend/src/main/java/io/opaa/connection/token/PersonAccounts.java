package io.opaa.connection.token;

import java.time.Instant;
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

  /**
   * Records that account {@code accountId} handed out its secret at {@code now}, unless a use after
   * {@code notBefore} is recorded already or another transaction holds the row; needs a
   * transaction. Returns the rows written, 0 or 1.
   */
  int markUsed(UUID accountId, Instant now, Instant notBefore);

  /** One connected account by the person and profile it joins. */
  interface AccountKey {
    UUID getId();

    UUID getUserId();

    UUID getProfileId();

    /** When it last handed out its secret, kept to the day; {@code null} for never. */
    Instant getLastUsedAt();
  }
}
