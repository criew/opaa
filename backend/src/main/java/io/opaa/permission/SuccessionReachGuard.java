package io.opaa.permission;

import io.opaa.api.types.SuccessionObjectType;
import java.util.UUID;

/**
 * The one rule an object without a capable responsible party imposes (#1819, ADR-0036 Entscheidung
 * 6): <b>its reach is frozen</b> - no new grant, no higher release level, no new provisioning, for
 * a space no new members. Everything else keeps working, and <b>nothing is deleted</b>.
 *
 * <p>Declared here because every business package asks it and implemented by {@code
 * io.opaa.succession}, which composes the three of them; the dependency stays {@code succession}
 * &rarr; {@code permission}, like every other port of this package.
 */
public interface SuccessionReachGuard {

  /**
   * Refuses the call with a conflict naming the addressee when this object's succession is open -
   * and does nothing at all otherwise. The message says what holds and who is responsible, so
   * whoever is refused knows where to go rather than only that they may not.
   */
  void requireReachNotFrozen(
      SuccessionObjectType objectType, UUID objectId, String attemptedAction);
}
