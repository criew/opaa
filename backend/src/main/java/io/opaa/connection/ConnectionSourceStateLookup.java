package io.opaa.connection;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.indexing.source.SourceStateLookup;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core's read-only port on whether a source is still updated, answered from the locks and the
 * library's connection - the same states {@link ProfileSourceConnectionResolver} refuses a run
 * with, without resolving a target or renewing a secret.
 */
@Component
public class ConnectionSourceStateLookup implements SourceStateLookup {

  static final String ADMINISTRATION = "Systemverwaltung";
  static final String LIBRARY_MANAGERS = "Verwaltende der Bibliothek";

  private final ConnectorLockService locks;
  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;

  public ConnectionSourceStateLookup(
      ConnectorLockService locks,
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles) {
    this.locks = locks;
    this.connections = connections;
    this.profiles = profiles;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, SourceState> frozenAmong(Collection<KnowledgeLibrary> libraries) {
    Map<UUID, SourceState> states = new HashMap<>();
    if (libraries.isEmpty()) {
      return states;
    }
    Set<UUID> locked = locks.lockNotices(libraries).keySet();
    Map<UUID, LibraryConnection> connectionOf =
        connections.findAllById(libraries.stream().map(KnowledgeLibrary::getId).toList()).stream()
            .collect(Collectors.toMap(LibraryConnection::getLibraryId, connection -> connection));
    Map<UUID, ConnectionProfile> profileOf =
        profiles
            .findAllById(
                connectionOf.values().stream()
                    .map(LibraryConnection::getProfileId)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(ConnectionProfile::getId, profile -> profile));
    for (KnowledgeLibrary library : libraries) {
      UUID id = library.getId();
      LibraryConnection connection = connectionOf.get(id);
      if (locked.contains(id)) {
        states.put(id, new SourceState(Reason.LOCKED, ADMINISTRATION));
      } else if (connection != null && connection.getProfileId() == null) {
        states.put(id, new SourceState(Reason.ACCESS_REMOVED, LIBRARY_MANAGERS));
      } else if (connection != null) {
        ConnectionProfile profile = profileOf.get(connection.getProfileId());
        if (profile != null) {
          notConnected(profile, library)
              .ifPresent(
                  responsible ->
                      states.put(id, new SourceState(Reason.NOT_CONNECTED, responsible)));
        }
      }
    }
    return states;
  }

  /** Who can connect {@code library} again, empty while it holds what its profile asks for. */
  private static Optional<String> notConnected(
      ConnectionProfile profile, KnowledgeLibrary library) {
    ConnectionAuthMethod method = profile.getAuthMethod();
    if (method == ConnectionAuthMethod.NONE) {
      return Optional.empty();
    }
    if (method == ConnectionAuthMethod.PERSONAL_SECRET) {
      return library.getSourceCredentials() == null
          ? Optional.of(LIBRARY_MANAGERS)
          : Optional.empty();
    }
    // the sign-in methods a library cannot be connected with yet, as the resolver says
    return Optional.of(ADMINISTRATION);
  }
}
