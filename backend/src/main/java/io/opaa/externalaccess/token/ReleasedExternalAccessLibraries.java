package io.opaa.externalaccess.token;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The release of a library for external access as #1731 stores it: the Reichweitenfeld on the
 * library itself, read per call.
 *
 * <p>{@link KnowledgeLibrary#isExternalAccessActive} is the one reader - it treats a Befristung
 * that has passed as gone the moment it passes, not when the daily run writes it down. A single
 * instance (ADR-0021) may be down for days, and a release must not outlive its end for that long.
 */
@Component
class ReleasedExternalAccessLibraries implements ExternalAccessLibraryRelease {

  private final KnowledgeLibraryRepository libraries;
  private final Clock clock;

  ReleasedExternalAccessLibraries(KnowledgeLibraryRepository libraries, Clock clock) {
    this.libraries = libraries;
    this.clock = clock;
  }

  @Override
  public List<KnowledgeLibrary> releasedLibrariesAmong(Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return List.of();
    }
    Instant now = clock.instant();
    return libraries.findAllById(libraryIds).stream()
        .filter(library -> library.isExternalAccessActive(now))
        .toList();
  }
}
