package io.opaa.connection.consent;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnection.Responsible;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The resting source connections of an organization for the system administration: every shared
 * library with an own consent whose source is not reached - expired, disconnected, locked or
 * without profile. Private libraries are never read here.
 */
@Service
public class DormantSourceConnections {

  private final LibraryConnectionRepository connections;
  private final KnowledgeLibraryRepository libraries;
  private final ConnectionProfileRepository profiles;
  private final SourceBlocks blocks;
  private final ConsentResponsibles responsibles;

  DormantSourceConnections(
      LibraryConnectionRepository connections,
      KnowledgeLibraryRepository libraries,
      ConnectionProfileRepository profiles,
      SourceBlocks blocks,
      ConsentResponsibles responsibles) {
    this.connections = connections;
    this.libraries = libraries;
    this.profiles = profiles;
    this.blocks = blocks;
    this.responsibles = responsibles;
  }

  /** The resting source connections of {@code organizationId}, by library name. */
  @Transactional(readOnly = true)
  public List<Dormant> of(UUID organizationId) {
    Map<UUID, LibraryConnection> byLibrary =
        connections.findSharedSourceConsentsIn(organizationId).stream()
            .collect(Collectors.toMap(LibraryConnection::getLibraryId, Function.identity()));
    if (byLibrary.isEmpty()) {
      return List.of();
    }
    List<KnowledgeLibrary> found = libraries.findAllById(byLibrary.keySet());
    Map<UUID, SourceBlock> blocked = blocks.blocksAmong(found, SourceBlocks.ALL);
    return found.stream()
        .filter(library -> !library.isOwnerOnly() && blocked.containsKey(library.getId()))
        .sorted(
            Comparator.comparing(KnowledgeLibrary::getName).thenComparing(KnowledgeLibrary::getId))
        .map(
            library -> {
              LibraryConnection connection = byLibrary.get(library.getId());
              ConnectionProfile profile =
                  connection.getProfileId() == null
                      ? null
                      : profiles.findById(connection.getProfileId()).orElse(null);
              Responsible responsible = connection.getResponsible().orElse(null);
              return new Dormant(
                  library.getId(),
                  library.getName(),
                  profile == null ? null : profile.getId(),
                  profile == null ? null : profile.getName(),
                  connection.getAccountLabel(),
                  blocked.get(library.getId()).reason(),
                  connection.getEndedCause(),
                  connection.getEndedAt(),
                  responsible,
                  responsibles.nameOf(responsible));
            })
        .toList();
  }

  /** One resting source connection; {@code null} where a value is unknown or does not apply. */
  public record Dormant(
      UUID libraryId,
      String libraryName,
      UUID profileId,
      String profileName,
      String accountLabel,
      Reason reason,
      ConnectionEndCause endedCause,
      Instant endedAt,
      Responsible responsible,
      String responsibleName) {}
}
