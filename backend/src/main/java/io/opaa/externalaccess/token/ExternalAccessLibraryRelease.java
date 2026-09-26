package io.opaa.externalaccess.token;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The release of a knowledge library for external access (ADR-0035, Entscheidung 4), as this
 * package needs it: which of a set of libraries carry a release that is valid right now.
 *
 * <p>The release itself - a historicised, time-limited Reichweitenfeld - belongs to {@code
 * io.opaa.library}; {@link ReleasedExternalAccessLibraries} reads it. The seam keeps the
 * intersection in {@link ExternalAccessTokenScopeService} independent of how the release is stored
 * and lets it be driven in both directions in a test.
 */
public interface ExternalAccessLibraryRelease {

  /**
   * The libraries among {@code libraryIds} whose release is in force at this moment. Asked per
   * call, like every other factor of the effective view - a release that was withdrawn takes its
   * library out of every token's view without any token being touched.
   *
   * <p>Returns the rows, not only their ids, so a caller that needs name and expiry - the selection
   * dialogue - does not read them a second time.
   */
  List<KnowledgeLibrary> releasedLibrariesAmong(Collection<UUID> libraryIds);

  /** The ids of {@link #releasedLibrariesAmong} - what the intersection of a scope needs. */
  default Set<UUID> releasedAmong(Collection<UUID> libraryIds) {
    return releasedLibrariesAmong(libraryIds).stream()
        .map(KnowledgeLibrary::getId)
        .collect(Collectors.toUnmodifiableSet());
  }
}
