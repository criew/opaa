package io.opaa.auth.local;

import java.util.Optional;
import java.util.UUID;

/**
 * What a local account holds in spaces, as the handover shows and audits it (ADR-0033, Entscheidung
 * 12). Implemented by {@code LocalAccountSpaceDirectoryAdapter} in the space package.
 */
public interface LocalAccountSpaceDirectory {

  /** The name of the account's personal space, if it has one. */
  Optional<String> personalSpaceName(UUID userId);

  /** How many space memberships the account holds directly. */
  long countSpaceMemberships(UUID userId);
}
