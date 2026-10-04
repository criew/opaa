package io.opaa.connection;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionSecrets;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.ProfileAdmission;
import io.opaa.connection.profile.SecretOwner;
import io.opaa.connection.profile.ServerAddress;
import io.opaa.indexing.source.SourceChangeGate;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which profile a library is connected through, for the library administration. The rights of the
 * caller are checked there; here only what the profile admits: the same connector, libraries as
 * owners, and an address under its server address.
 */
@Service
@Transactional(readOnly = true)
public class LibraryConnectionService {

  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;
  private final KnowledgeLibraryRepository libraries;
  private final SourceConnectorRegistry connectors;
  private final ConnectorLockService locks;
  private final ConnectionSecrets secrets;
  private final SourceChangeGate changeGate;
  private final Clock clock;

  public LibraryConnectionService(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      KnowledgeLibraryRepository libraries,
      SourceConnectorRegistry connectors,
      ConnectorLockService locks,
      ConnectionSecrets secrets,
      Clock clock) {
    this.connections = connections;
    this.profiles = profiles;
    this.libraries = libraries;
    this.connectors = connectors;
    this.locks = locks;
    this.secrets = secrets;
    this.changeGate = new SourceChangeGate(connectors);
    this.clock = clock;
  }

  /** The note of a locked type or profile the library carries, empty while it is not locked. */
  public Optional<String> lockNotice(KnowledgeLibrary library) {
    return locks.lockNotice(library);
  }

  /** {@link #lockNotice} for a whole page of libraries; a library that is not locked is absent. */
  public Map<UUID, String> lockNotices(Collection<KnowledgeLibrary> libraries) {
    return locks.lockNotices(libraries);
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
   * Refuses {@code requestedUrl} for {@code library} when the library is connected and the address
   * leaves its profile; a library without a connection is free.
   */
  public void requireAddressAllowed(KnowledgeLibrary library, String requestedUrl) {
    connections
        .findById(library.getId())
        .map(LibraryConnection::getProfileId)
        .flatMap(profiles::findById)
        .ifPresent(profile -> requireUnder(profile, requestedUrl));
  }

  /**
   * Connects a saved library through {@code profileId}. An address under the previous profile moves
   * under the new one; a secret is kept only while the origin and the connector's binding stay.
   */
  @Transactional
  public ConnectionProfile connect(KnowledgeLibrary library, UUID profileId) {
    ConnectionProfile profile = requireAdmitting(profileId, library.getSourceType());
    LibraryConnection connection = connections.findById(library.getId()).orElse(null);
    String address = library.getSourceUrl();
    ConnectionProfile previous =
        connection == null || connection.getProfileId() == null
            ? null
            : profiles.findById(connection.getProfileId()).orElse(null);
    if (previous != null
        && !previous.getId().equals(profile.getId())
        && ServerAddress.covers(previous.getServerUrl(), address)) {
      address = ServerAddress.rebase(address, previous.getServerUrl(), profile.getServerUrl());
    } else if (address == null) {
      address = profile.getServerUrl();
    }
    requireUnder(profile, address);
    if (!address.equals(library.getSourceUrl())) {
      boolean keepsSecret =
          ServerAddress.sameOrigin(library.getSourceUrl(), address)
              && connectors
                  .connector(library.getSourceType())
                  .keepsCredentials(library.getSourceUrl(), address);
      library.moveSourceUrl(address);
      libraries.save(library);
      if (!keepsSecret) {
        secrets.discard(SecretOwner.of(profile.getId(), library));
      }
      changeGate.addressMoved(library);
    }
    if (connection == null) {
      connections.save(new LibraryConnection(library.getId(), profile.getId(), clock.instant()));
    } else {
      connection.moveTo(profile.getId(), clock.instant());
      connections.save(connection);
    }
    return profile;
  }

  /**
   * Releases the library from its profile; it keeps address and secret. Refused for a connector
   * that requires a profile.
   */
  @Transactional
  public void disconnect(KnowledgeLibrary library) {
    if (connectors.descriptor(library.getSourceType()).profileDeclaration().support()
        == ConnectionProfileSupport.REQUIRED) {
      throw new ValidationException("Diese Quellart ist nur über einen Zugang nutzbar");
    }
    connections.findById(library.getId()).ifPresent(connections::delete);
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
