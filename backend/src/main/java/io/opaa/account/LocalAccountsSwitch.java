package io.opaa.account;

import io.opaa.auth.OidcProvider;
import java.util.UUID;

/**
 * The switch of the local account management: the {@code enabled} flag of the LOCAL provider row
 * (ADR-0033, Entscheidung 4). Implemented by the provider administration in {@code
 * io.opaa.directory}, which audits the change and, switching off, ends the sessions of every
 * regular local account.
 */
public interface LocalAccountsSwitch {

  /**
   * Sets the switch by value; a missing LOCAL row is created switched off first, so the change is
   * audited like any other.
   */
  Result setLocalAccountsEnabled(UUID organizationId, UUID actorUserId, boolean enabled);

  /** The LOCAL row after the switch and how many regular accounts had a session ended. */
  record Result(OidcProvider provider, int revokedSessions) {}
}
