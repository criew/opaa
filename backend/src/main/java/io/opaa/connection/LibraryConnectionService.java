package io.opaa.connection;

import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionSecrets;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.ProfileAdmission;
import io.opaa.connection.profile.ProfileRequirementService;
import io.opaa.connection.profile.ProfileRequirements;
import io.opaa.connection.profile.SecretOwner;
import io.opaa.connection.profile.ServerAddress;
import io.opaa.connection.profile.SourceTransitions;
import io.opaa.connection.profile.SourceTransitions.Move;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which profile a library is connected through, for the library administration. The rights of the
 * caller are checked there; here only what the profile admits: the same connector, libraries as
 * owners, and an address under its server address. Connecting and releasing a saved library pass
 * its connector ({@link SourceTransitions}).
 */
@Service
@Transactional(readOnly = true)
public class LibraryConnectionService {

  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;
  private final KnowledgeLibraryRepository libraries;
  private final SourceConnectorRegistry connectors;
  private final ConnectorLockService locks;
  private final ProfileRequirements requirements;
  private final ProfileRequirementService requirementService;
  private final ConnectionSecrets secrets;
  private final EffectiveSourceSettings effective;
  private final SourceTransitions transitions;
  private final Clock clock;

  public LibraryConnectionService(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      KnowledgeLibraryRepository libraries,
      SourceConnectorRegistry connectors,
      ConnectorLockService locks,
      ProfileRequirements requirements,
      ProfileRequirementService requirementService,
      ConnectionSecrets secrets,
      EffectiveSourceSettings effective,
      SourceTransitions transitions,
      Clock clock) {
    this.connections = connections;
    this.profiles = profiles;
    this.libraries = libraries;
    this.connectors = connectors;
    this.locks = locks;
    this.requirements = requirements;
    this.requirementService = requirementService;
    this.secrets = secrets;
    this.effective = effective;
    this.transitions = transitions;
    this.clock = clock;
  }

  /** The lock the library carries, empty while none of the system administration's holds it. */
  public Optional<SourceBlock> lockOf(KnowledgeLibrary library) {
    return locks.lockOf(library);
  }

  /** {@link #lockOf} for a whole page of libraries; a library that is not locked is absent. */
  public Map<UUID, SourceBlock> locksOf(Collection<KnowledgeLibrary> libraries) {
    return locks.locksOf(libraries);
  }

  /** The connection of {@code libraryId}, empty for a library with its own address. */
  public Optional<LibraryConnectionView> connectionOf(UUID libraryId) {
    return connections
        .findById(libraryId)
        .map(
            connection ->
                new LibraryConnectionView(
                    Optional.ofNullable(connection.getProfileId())
                        .flatMap(profiles::findById)
                        .orElse(null)));
  }

  /**
   * The address a new library of {@code sourceType} on profile {@code profileId} is created with:
   * {@code requestedUrl}, which must lie under the profile, or the profile's address itself.
   */
  public String addressForNewLibrary(UUID profileId, SourceType sourceType, String requestedUrl) {
    ConnectionProfile profile = requireAdmitting(profileId, sourceType);
    return requireUnder(profile, requestedUrl == null ? profile.getServerUrl() : requestedUrl);
  }

  /**
   * Refuses {@code requestedUrl} for {@code library} when the address leaves its profile; a library
   * with its own address - also one whose profile was deleted - keeps it while its type is usable
   * only through a profile, and is free otherwise.
   */
  public void requireAddressAllowed(KnowledgeLibrary library, String requestedUrl) {
    LibraryConnection connection = connections.findById(library.getId()).orElse(null);
    if (!LibraryConnection.throughProfile(connection)) {
      requirementService.requireOwnAddressKept(library, requestedUrl);
      return;
    }
    profiles
        .findById(connection.getProfileId())
        .ifPresent(profile -> requireUnder(profile, requestedUrl));
  }

  /**
   * Records that a library just created through {@code profileId} - validated and stored with the
   * profile's frame already - is connected through it.
   */
  @Transactional
  public void attachNew(KnowledgeLibrary library, UUID profileId) {
    requireAdmitting(profileId, library.getSourceType());
    connections.save(new LibraryConnection(library.getId(), profileId, clock.instant()));
  }

  /**
   * Connects a saved library through {@code profileId} at {@code requestedUrl}, which must lie
   * under the profile; without one an address under the previous profile moves under the new one.
   * The connector checks the configuration the profile gives the library (German 400 when it
   * refuses); then the library adopts the frame (proxy, TLS switch, bound defaults - own values
   * give way), and its secret stays only while its {@link io.opaa.connection.profile.SecretTarget}
   * does.
   *
   * @return the fields the move changed, as the audit of a direct change names them
   */
  @Transactional
  public Set<String> connect(KnowledgeLibrary library, UUID profileId, String requestedUrl) {
    ConnectionProfile profile = requireAdmitting(profileId, library.getSourceType());
    LibraryConnection connection = connections.findById(library.getId()).orElse(null);
    ConnectionProfile previous =
        !LibraryConnection.throughProfile(connection)
            ? null
            : profiles.findById(connection.getProfileId()).orElse(null);
    String address = library.getSourceUrl();
    if (requestedUrl != null) {
      address =
          connectors.connector(library.getSourceType()).normalizeSourceUrl(requestedUrl.trim());
    } else if (previous != null
        && !previous.getId().equals(profile.getId())
        && ServerAddress.covers(previous.getServerUrl(), address)) {
      address = ServerAddress.rebase(address, previous.getServerUrl(), profile.getServerUrl());
    } else if (address == null) {
      address = profile.getServerUrl();
    }
    requireUnder(profile, address);
    Move move =
        transitions.move(
            library, Optional.ofNullable(previous), Optional.of(profile), address, false);
    transitions.require(move);
    if (!address.equals(library.getSourceUrl())) {
      library.moveSourceUrl(address);
    }
    if (move.discardsSecret()) {
      secrets.discard(SecretOwner.of(profile.getId(), library));
    }
    effective.adoptFrame(library, profile);
    libraries.save(library);
    if (connection == null) {
      connections.save(new LibraryConnection(library.getId(), profile.getId(), clock.instant()));
    } else {
      connection.moveTo(profile.getId(), clock.instant());
      connections.save(connection);
    }
    return transitions.applied(move);
  }

  /**
   * Releases the library from its profile; it keeps address and secret, and what the profile set
   * for it - defaults, proxy, TLS switch - becomes its own, so it runs on unchanged. Refused while
   * its type is usable only through a profile.
   *
   * @return the fields the release changed for the connector, none when it runs on unchanged
   */
  @Transactional
  public Set<String> disconnect(KnowledgeLibrary library) {
    if (requirements.profileRequired(library.getSourceType())) {
      throw requirementService.ownAddressRefused(library.getSourceType());
    }
    LibraryConnection connection = connections.findById(library.getId()).orElse(null);
    if (connection == null) {
      return Set.of();
    }
    Optional<ConnectionProfile> profile =
        LibraryConnection.throughProfile(connection)
            ? profiles.findById(connection.getProfileId())
            : Optional.empty();
    Move move = transitions.release(library, profile);
    transitions.require(move);
    profile.ifPresent(found -> effective.releaseFrame(library, found));
    libraries.save(library);
    connections.delete(connection);
    return transitions.applied(move);
  }

  private ConnectionProfile requireAdmitting(UUID profileId, SourceType sourceType) {
    return ProfileAdmission.require(
        profiles.findById(profileId), sourceType, connectors.descriptor(sourceType));
  }

  private static String requireUnder(ConnectionProfile profile, String address) {
    if (!ServerAddress.covers(profile.getServerUrl(), address)) {
      throw new ValidationException(
          "Die Adresse muss unter der Server-Adresse des Zugangs liegen: "
              + profile.getServerUrl());
    }
    return address;
  }

  /** The profile of a connection, {@code null} once it was deleted ("Zugang entfernt"). */
  public record LibraryConnectionView(ConnectionProfile profile) {

    public boolean removed() {
      return profile == null;
    }
  }
}
