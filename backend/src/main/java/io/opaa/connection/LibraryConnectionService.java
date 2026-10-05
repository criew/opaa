package io.opaa.connection;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.consent.SourceConsentService.ConsentView;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnection.Responsible;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.ProfileAdmission;
import io.opaa.connection.profile.ProfileRequirementService;
import io.opaa.connection.profile.ProfileRequirements;
import io.opaa.connection.profile.ServerAddress;
import io.opaa.connection.profile.SourceDraft.DraftOwner;
import io.opaa.connection.profile.SourceTransitions;
import io.opaa.connection.profile.SourceTransitions.Move;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which profile a library is connected through, for the library administration. The rights of the
 * caller are checked there; here only what the profile admits: the same connector, libraries as
 * owners, and an address under its server address. Connecting and releasing a saved library pass
 * its connector ({@link SourceTransitions}).
 *
 * <p>Every write of a library through a profile holds the profile row as it read it (shared, after
 * the connector's check, before its first write); a change of the profile holds the row exclusively
 * from its first statement. So the change either sees the library, or the write is refused with 409
 * {@value #PROFILE_CHANGED}. A library's own consent ({@link SourceConsentService}) ends - deleted
 * and revoked - when the library leaves its profile or is deleted.
 */
@Service
@Transactional(readOnly = true)
public class LibraryConnectionService {

  /** Refusal of a write through a profile that changed since the write read it. */
  public static final String PROFILE_CHANGED = "LIBRARY_CONNECTION_PROFILE_CHANGED";

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
  private final SourceConsentService consents;
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
      SourceConsentService consents,
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
    this.consents = consents;
    this.clock = clock;
  }

  /** The own source consent of {@code library} as its managers see it, empty for none. */
  public Optional<ConsentView> consentOf(KnowledgeLibrary library) {
    return consents.viewOf(library);
  }

  /**
   * Hands the caller's pending source consent {@code pendingConnectionId} to {@code library}, just
   * created through {@code profileId}; see {@link SourceConsentService#takeOver}.
   */
  @Transactional
  public void takeOverConsent(
      KnowledgeLibrary library,
      UUID profileId,
      UUID pendingConnectionId,
      Responsible responsible,
      CurrentUser caller) {
    ConnectionProfile profile =
        profiles.findById(profileId).orElseThrow(() -> new IllegalStateException("no profile"));
    consents.takeOver(caller, library, profile, pendingConnectionId, responsible);
  }

  /** Disconnects the own source consent of {@code libraryId}; see {@link SourceConsentService}. */
  @Transactional
  public void disconnectConsent(UUID libraryId, CurrentUser caller) {
    consents.disconnect(caller, libraryId);
  }

  /** Ends the own source consent of {@code library} before the library is deleted. */
  @Transactional
  public void libraryDeleted(KnowledgeLibrary library, CurrentUser caller) {
    consents.forget(library, ConnectionEndCause.LIBRARY_DELETED, caller.id());
  }

  /** The lock the library carries, empty while none of the system administration's holds it. */
  public Optional<SourceBlock> lockOf(KnowledgeLibrary library) {
    return locks.lockOf(library);
  }

  /** {@link #lockOf} for a whole page of libraries; a library that is not locked is absent. */
  public Map<UUID, SourceBlock> locksOf(Collection<KnowledgeLibrary> libraries) {
    return locks.locksOf(libraries);
  }

  /**
   * The block {@code library} shows its readers: its lock or lapsed own consent, for a private
   * library every reason (see {@link ConnectorLockService#shownAmong}).
   */
  public Optional<SourceBlock> shownBlockOf(KnowledgeLibrary library) {
    return Optional.ofNullable(locks.shownAmong(List.of(library)).get(library.getId()));
  }

  /** {@link #shownBlockOf} for a whole page of libraries; a free library is absent. */
  public Map<UUID, SourceBlock> shownBlocksOf(Collection<KnowledgeLibrary> libraries) {
    return locks.shownAmong(libraries);
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
    holdUnchanged(requireAdmitting(profileId, library));
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
    return connect(library, profileId, requestedUrl, null);
  }

  /**
   * {@link #connect(KnowledgeLibrary, UUID, String)} by {@code actorUserId}, who is logged as
   * ending the library's own consent when it leaves its OAuth profile; {@code null} for the system.
   */
  @Transactional
  public Set<String> connect(
      KnowledgeLibrary library, UUID profileId, String requestedUrl, UUID actorUserId) {
    ConnectionProfile profile = requireAdmitting(profileId, library);
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
    UUID previousId = previous == null ? null : previous.getId();
    boolean leavesConsent =
        previous != null
            && previous.getAuthMethod() == ConnectionAuthMethod.OAUTH
            && !previous.getId().equals(profile.getId());
    Move move =
        transitions.move(
            library,
            Optional.ofNullable(previous),
            Optional.of(profile),
            address,
            false,
            transitions.holdingSecrets(List.of(library), previousId).contains(library.getId()));
    transitions.require(move);
    holdUnchanged(profile, previous);
    if (leavesConsent) {
      consents.forget(library, ConnectionEndCause.SELF, actorUserId);
    }
    if (!address.equals(library.getSourceUrl())) {
      library.moveSourceUrl(address);
    }
    if (move.discardsSecret()) {
      // only the library's own secret: a person's is shared by every library on the account
      secrets.discard(new LibraryOwned(library.getId()));
    }
    transitions.keepDefaults(move);
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
    return disconnect(library, null);
  }

  /**
   * {@link #disconnect(KnowledgeLibrary)} by {@code actorUserId}, logged as ending the library's
   * own consent; {@code null} for the system.
   */
  @Transactional
  public Set<String> disconnect(KnowledgeLibrary library, UUID actorUserId) {
    requireReleasable(library);
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
    holdUnchanged(profile.orElse(null));
    consents.forget(library, ConnectionEndCause.SELF, actorUserId);
    profile.ifPresent(found -> effective.releaseFrame(library, found));
    libraries.save(library);
    connections.delete(connection);
    return transitions.applied(move);
  }

  /**
   * Holds the profile {@code library} is connected through, for a change of its own configuration
   * validated against the profile's frame: called after the connector's check and before the first
   * write, refused like {@link #connect} when the profile changed meanwhile.
   */
  @Transactional
  public void holdProfileOf(KnowledgeLibrary library) {
    LibraryConnection connection = connections.findById(library.getId()).orElse(null);
    if (LibraryConnection.throughProfile(connection)) {
      holdUnchanged(profiles.findById(connection.getProfileId()).orElse(null));
    }
  }

  /**
   * Holds each of {@code read} - in the version this transaction read - against a change until the
   * transaction ends, in the order of their ids; 409 {@value #PROFILE_CHANGED} when one changed or
   * went since.
   */
  private void holdUnchanged(ConnectionProfile... read) {
    List<ConnectionProfile> held =
        Stream.of(read)
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(ConnectionProfile::getId))
            .toList();
    for (ConnectionProfile profile : held) {
      Long version = profiles.lockedVersion(profile.getId());
      if (version == null || version != profile.getVersion()) {
        throw new ConflictException(
            "Der Zugang „"
                + profile.getName()
                + "“ wurde soeben geändert. Bitte versuchen Sie es erneut.",
            PROFILE_CHANGED);
      }
    }
  }

  /**
   * Refuses (German 400) releasing a private library to an own address: it runs on its owner's
   * connected account only.
   */
  public static void requireReleasable(KnowledgeLibrary library) {
    if (library.isOwnerOnly()) {
      throw new ValidationException(
          "Eine private Bibliothek läuft nur über das verbundene Konto ihrer Besitzerin und lässt"
              + " sich keinem Zugang entziehen. Ordnen Sie sie einem anderen Zugang zu oder"
              + " löschen Sie sie.");
    }
  }

  private ConnectionProfile requireAdmitting(UUID profileId, SourceType sourceType) {
    return ProfileAdmission.require(
        profiles.findById(profileId), sourceType, connectors.descriptor(sourceType));
  }

  /**
   * A private library goes onto a profile admitting persons, every other onto one for libraries.
   */
  private ConnectionProfile requireAdmitting(UUID profileId, KnowledgeLibrary library) {
    SourceType sourceType = library.getSourceType();
    return ProfileAdmission.require(
        profiles.findById(profileId),
        sourceType,
        connectors.descriptor(sourceType),
        library.isOwnerOnly() ? DraftOwner.PERSON : DraftOwner.LIBRARY);
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
