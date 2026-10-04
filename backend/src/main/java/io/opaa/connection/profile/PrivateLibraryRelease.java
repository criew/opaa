package io.opaa.connection.profile;

import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a change of a profile does to the private libraries on it: none of them vetoes it. One the
 * connector refuses, and every one on a profile that stops admitting persons, is released from the
 * profile and rests as {@code ACCESS_REMOVED} until its owner moves it; the administration learns
 * only how many were refused, never which or why.
 */
@Component
class PrivateLibraryRelease {

  private final LibraryConnectionRepository connections;
  private final KnowledgeLibraryRepository libraries;
  private final SourceTransitions transitions;
  private final Clock clock;

  PrivateLibraryRelease(
      LibraryConnectionRepository connections,
      KnowledgeLibraryRepository libraries,
      SourceTransitions transitions,
      Clock clock) {
    this.connections = connections;
    this.libraries = libraries;
    this.transitions = transitions;
    this.clock = clock;
  }

  /** {@code rejections} split into the vetoes of shared libraries and the private ones released. */
  static Verdict judge(List<ChangeRejection> rejections, Set<UUID> privateLibraries) {
    List<ChangeRejection> vetoes = new ArrayList<>();
    Set<UUID> released = new HashSet<>();
    for (ChangeRejection rejection : rejections) {
      if (privateLibraries.contains(rejection.libraryId())) {
        released.add(rejection.libraryId());
      } else {
        vetoes.add(rejection);
      }
    }
    return new Verdict(List.copyOf(vetoes), Set.copyOf(released));
  }

  /** Releases {@code libraryIds} from their profile, each with the audit entry of a move. */
  void release(CurrentUser caller, Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return;
    }
    for (LibraryConnection connection : connections.findAllById(libraryIds)) {
      connection.moveTo(null, clock.instant());
      connections.save(connection);
    }
    for (KnowledgeLibrary library : libraries.findAllById(libraryIds)) {
      transitions.record(caller, library, Set.of("connectionProfile"));
    }
  }

  /**
   * @param vetoes the refusals that keep the change from happening, of shared libraries
   * @param released the private libraries the connector refused, to be released
   */
  record Verdict(List<ChangeRejection> vetoes, Set<UUID> released) {}
}
