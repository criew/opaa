package io.opaa.auth.local;

import java.util.UUID;

/**
 * What a local account holds in groups, as the handover shows and audits it (ADR-0033, Entscheidung
 * 12). Implemented by {@code LocalAccountGroupDirectoryAdapter} in the group package.
 */
public interface LocalAccountGroupDirectory {

  /** How many group memberships the account holds. */
  long countGroupMemberships(UUID userId);
}
