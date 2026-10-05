package io.opaa.connection.profile;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What a change of a profile does to the private libraries on it: none of them vetoes it. One the
 * connector refuses, and every one on a profile that stops admitting persons, is released from the
 * profile and rests as {@code ACCESS_REMOVED} until its owner moves it; she is told, the
 * administration learns only a masked number, never which or why.
 */
@Component
class PrivateLibraryRelease {

  private final LibraryConnectionRepository connections;
  private final KnowledgeLibraryRepository libraries;
  private final SourceTransitions transitions;
  private final NotificationService notifications;
  private final Clock clock;

  PrivateLibraryRelease(
      LibraryConnectionRepository connections,
      KnowledgeLibraryRepository libraries,
      SourceTransitions transitions,
      NotificationService notifications,
      Clock clock) {
    this.connections = connections;
    this.libraries = libraries;
    this.transitions = transitions;
    this.notifications = notifications;
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

  /**
   * Releases {@code libraryIds} from their profile {@code profile}, each with the audit entry of a
   * move and a notification to its owner.
   */
  void release(CurrentUser caller, ConnectionProfile profile, Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return;
    }
    for (LibraryConnection connection : connections.findAllById(libraryIds)) {
      connection.moveTo(null, clock.instant());
      connections.save(connection);
    }
    for (KnowledgeLibrary library : libraries.findAllById(libraryIds)) {
      transitions.record(caller, library, Set.of("connectionProfile"));
      notifications.notify(
          library.getOrganizationId(),
          library.getOwnerUserId(),
          NotificationType.PRIVATE_LIBRARY_RELEASED,
          AuditObjectType.KNOWLEDGE_LIBRARY,
          library.getId(),
          "Private Bibliothek vom Zugang „" + profile.getName() + "“ gelöst",
          "Die Systemverwaltung hat den Zugang „"
              + profile.getName()
              + "“ geändert, und Ihre private Bibliothek „"
              + library.getName()
              + "“ läuft darüber nicht mehr. Ihr Inhalt bleibt erhalten und durchsuchbar, wird"
              + " aber nicht mehr aktualisiert. Ordnen Sie die Bibliothek einem anderen Zugang zu,"
              + " auf dem Sie ein verbundenes Konto haben; gibt es keinen, ist die"
              + " Systemverwaltung zuständig.");
    }
  }

  /**
   * How many private libraries {@code released} are, masked by their owners as a part of the
   * private libraries of each of their organizations - a profile has none of its own.
   */
  PersonCount count(Collection<KnowledgeLibrary> released, PersonNumbers numbers) {
    Set<UUID> ids = released.stream().map(KnowledgeLibrary::getId).collect(Collectors.toSet());
    List<PersonNumbers.OwnerSplit> organizations = new ArrayList<>();
    released.stream()
        .collect(
            Collectors.groupingBy(
                KnowledgeLibrary::getOrganizationId,
                Collectors.mapping(KnowledgeLibrary::getOwnerUserId, Collectors.toSet())))
        .forEach(
            (organization, owners) ->
                organizations.add(
                    new PersonNumbers.OwnerSplit(
                        owners.size(),
                        libraries.countPrivateLibraryOwnersOutside(organization, ids))));
    return numbers.privateLibraries(ids.size(), organizations);
  }

  /**
   * @param vetoes the refusals that keep the change from happening, of shared libraries
   * @param released the private libraries the connector refused, to be released
   */
  record Verdict(List<ChangeRejection> vetoes, Set<UUID> released) {}
}
