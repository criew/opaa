package io.opaa.externalaccess.token;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The release of a knowledge library for external access (ADR-0035, Entscheidung 4), as this
 * package needs it: which of a set of libraries carry a release that is valid right now.
 *
 * <p>The release itself - a historicised, time-limited Reichweitenfeld - belongs to #1731. This
 * seam exists so the token machinery can be built without it and so plugging it in is one bean
 * rather than a change to the intersection in {@link ExternalAccessTokenScopeService}.
 */
public interface ExternalAccessLibraryRelease {

  /**
   * The subset of {@code libraryIds} whose release is in force at this moment. Asked per call, like
   * every other factor of the effective view - a release that was withdrawn takes its library out
   * of every token's view without any token being touched.
   */
  Set<UUID> releasedAmong(Collection<UUID> libraryIds);
}
