package io.opaa.connection.consent;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.Capability;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.NotificationType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.connection.log.ConnectionLog;
import io.opaa.connection.log.ConnectionLogActor;
import io.opaa.connection.log.ConnectionLogOwner;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.ConnectorScope;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnection.Responsible;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.ProfileAdmission;
import io.opaa.connection.profile.SourceConsentEnds;
import io.opaa.connection.profile.SourceDraft.DraftOwner;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.ConnectionSecrets.PendingView;
import io.opaa.connection.token.NewSecret;
import io.opaa.connection.token.SecretOwner.PendingConsent;
import io.opaa.connection.token.SecretOwner.SourceConsent;
import io.opaa.connection.token.SourceConsentRejections;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceChangeGate;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.notification.NotificationService;
import io.opaa.permission.CapabilityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A library's own OAuth consent with a service account ("Quelle verbinden", ADR-0041): who may give
 * it, how it is stored - with the library as owner, so it outlasts the person who gave it - and the
 * one way it ends for every cause, each logged as the library's connection and told to those
 * responsible where the person managing it did not cause it. A grant given before its library
 * exists waits for that person as a pending consent.
 */
@Service
public class SourceConsentService implements SourceConsentRejections, SourceConsentEnds {

  /** Refusal of a reconnection as another account at the provider than before, unconfirmed. */
  public static final String ACCOUNT_CHANGED = "ACCOUNT_CHANGED";

  /** Refusal of a new library with a pending consent that is gone or not the caller's. */
  public static final String PENDING_CONNECTION_UNUSABLE = "PENDING_CONNECTION_UNUSABLE";

  /** How long a consent given before its library exists waits for the library. */
  public static final Duration PENDING_LIFETIME = Duration.ofMinutes(60);

  private static final Capability RELEASE = Capability.CREATE_CONNECTOR_LIBRARY;

  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;
  private final KnowledgeLibraryRepository libraries;
  private final LibraryAccessService access;
  private final ConnectionSecrets secrets;
  private final EffectiveSourceSettings effective;
  private final ConnectorLockService locks;
  private final CapabilityService capabilities;
  private final ConsentResponsibles responsibles;
  private final ConnectionLog log;
  private final NotificationService notifications;
  private final AuditEventRecorder audit;
  private final Clock clock;

  /** Looked up per call: the core's port depends on this package, the connectors on the core. */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  SourceConsentService(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      KnowledgeLibraryRepository libraries,
      LibraryAccessService access,
      ConnectionSecrets secrets,
      EffectiveSourceSettings effective,
      ConnectorLockService locks,
      CapabilityService capabilities,
      ConsentResponsibles responsibles,
      ConnectionLog log,
      NotificationService notifications,
      AuditEventRecorder audit,
      Clock clock,
      ObjectProvider<SourceConnectorRegistry> connectors) {
    this.connections = connections;
    this.profiles = profiles;
    this.libraries = libraries;
    this.access = access;
    this.secrets = secrets;
    this.effective = effective;
    this.locks = locks;
    this.capabilities = capabilities;
    this.responsibles = responsibles;
    this.log = log;
    this.notifications = notifications;
    this.audit = audit;
    this.clock = clock;
    this.connectors = connectors;
  }

  /**
   * Refuses starting a consent of {@code caller} on {@code profile} for a new library: {@code 400}
   * where the profile or its connector does not let a library own an OAuth connection, {@code 403
   * CONNECTOR_LOCKED} on a lock and {@code 403 CAPABILITY_REQUIRED} without the profile's release -
   * what creating the library would need.
   */
  @Transactional(readOnly = true)
  public void requireStartableForNew(CurrentUser caller, ConnectionProfile profile) {
    requireLibraryConsent(profile);
    locks.requireUnlocked(profile.getSourceType(), profile);
    capabilities.requireCapability(
        caller,
        RELEASE,
        ConnectorScope.ofProfile(profile.getId()),
        "den Zugang „" + profile.getName() + "“");
  }

  /**
   * The library {@code libraryId} once {@code caller} may connect its source anew through {@code
   * profile}: {@code 404} for a library the caller does not see, {@code 403} below {@code MANAGER}
   * and on a locked profile, {@code 400} for a private library or one not on {@code profile}. No
   * release is needed: connecting anew is no new library.
   */
  @Transactional(readOnly = true)
  public KnowledgeLibrary requireReconnectable(
      CurrentUser caller, ConnectionProfile profile, UUID libraryId) {
    KnowledgeLibrary library = managedLibrary(caller, libraryId);
    requireLibraryConsent(profile);
    if (!profile
        .getId()
        .equals(
            connections
                .findById(library.getId())
                .map(LibraryConnection::getProfileId)
                .orElse(null))) {
      throw new ValidationException(
          "Die Bibliothek ist nicht über den Zugang „" + profile.getName() + "“ verbunden.");
    }
    locks.requireUnlocked(profile.getSourceType(), profile);
    return library;
  }

  /**
   * Refuses (German 400) {@code responsible} for {@code library}: only a person or group managing
   * it.
   */
  public void requireResponsible(KnowledgeLibrary library, Responsible responsible) {
    responsibles.require(library, responsible);
  }

  /**
   * Holds {@code grant}, which {@code caller} obtained on {@code profile} as {@code accountLabel},
   * for a library not created yet.
   */
  @Transactional
  public PendingConsent holdPending(
      CurrentUser caller,
      ConnectionProfile profile,
      String accountLabel,
      NewSecret.OAuthGrant grant) {
    return secrets.storePending(
        profile.getId(),
        caller.id(),
        accountLabel,
        grant,
        effective.consentTarget(profile, null),
        PENDING_LIFETIME);
  }

  /** The pending consent of {@code caller} with id {@code tokenId}, empty for none usable. */
  @Transactional(readOnly = true)
  public Optional<PendingView> pending(CurrentUser caller, UUID tokenId) {
    return secrets.pendingView(new PendingConsent(tokenId, caller.id()));
  }

  /**
   * Stores {@code grant}, which {@code caller} obtained as {@code accountLabel}, as the consent of
   * {@code library} on {@code profile} - in the caller's transaction, which holds the profile row.
   * {@code responsible} replaces the one named so far, else the caller answers for a first consent.
   * A consent given as another account than before needs {@code acceptsAccountChange}, and then
   * discards the library's run state.
   *
   * @throws ConflictException 409 {@value #ACCOUNT_CHANGED} for another account unconfirmed, 409
   *     when the library left the profile meanwhile
   */
  @Transactional
  public void establish(
      CurrentUser caller,
      KnowledgeLibrary library,
      ConnectionProfile profile,
      NewSecret.OAuthGrant grant,
      String accountLabel,
      Responsible responsible,
      boolean acceptsAccountChange) {
    KnowledgeLibrary current =
        libraries
            .findById(library.getId())
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    if (responsible != null) {
      responsibles.require(current, responsible);
    }
    LibraryConnection connection =
        connections
            .findById(library.getId())
            .filter(found -> profile.getId().equals(found.getProfileId()))
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Die Bibliothek ist inzwischen nicht mehr über den Zugang „"
                            + profile.getName()
                            + "“ verbunden. Es wurde nichts verbunden."));
    String before = connection.getAccountLabel();
    boolean accountChanged =
        before != null && accountLabel != null && !before.equalsIgnoreCase(accountLabel);
    if (accountChanged && !acceptsAccountChange) {
      throw new ConflictException(
          "Sie haben sich beim Anbieter als „"
              + accountLabel
              + "“ angemeldet; die Quelle war als „"
              + before
              + "“ verbunden. Bestätigen Sie den Wechsel des Kontos und verbinden Sie erneut –"
              + " der Abgleichstand der Bibliothek wird dann verworfen. Es wurde nichts"
              + " verbunden.",
          ACCOUNT_CHANGED);
    }
    boolean reconnected = connection.getConnectedAt() != null;
    secrets.store(
        new SourceConsent(profile.getId(), library.getId()),
        grant,
        effective.consentTarget(profile, current));
    connection.consented(
        accountLabel,
        caller.id(),
        responsible != null
            ? responsible
            : connection.getResponsible().orElse(Responsible.user(caller.id())),
        clock.instant());
    connections.save(connection);
    if (accountChanged) {
      new SourceChangeGate(connectors.getObject()).accountChanged(current);
    }
    logConnected(caller, current, profile, accountLabel, reconnected);
  }

  /**
   * Hands the pending consent {@code tokenId} of {@code caller} to {@code library}, just created on
   * {@code profile}, with {@code responsible} - or the caller - answering for it.
   *
   * @throws ConflictException 409 {@value #PENDING_CONNECTION_UNUSABLE} for a consent gone,
   *     expired, another person's, on another profile or for another target
   */
  @Transactional
  public void takeOver(
      CurrentUser caller,
      KnowledgeLibrary library,
      ConnectionProfile profile,
      UUID tokenId,
      Responsible responsible) {
    requireLibraryConsent(profile);
    PendingConsent pending = new PendingConsent(tokenId, caller.id());
    String accountLabel = secrets.pendingView(pending).map(PendingView::accountLabel).orElse(null);
    Responsible answering = responsible == null ? Responsible.user(caller.id()) : responsible;
    responsibles.require(library, answering);
    if (!secrets.takeOver(
        pending,
        new SourceConsent(profile.getId(), library.getId()),
        effective.consentTarget(profile, library))) {
      throw new ConflictException(
          "Die Verbindung der Quelle ist abgelaufen oder gehört nicht zu diesem Zugang. Bitte"
              + " verbinden Sie die Quelle erneut.",
          PENDING_CONNECTION_UNUSABLE);
    }
    LibraryConnection connection =
        connections
            .findById(library.getId())
            .orElseThrow(() -> new IllegalStateException("a new library is connected first"));
    connection.consented(accountLabel, caller.id(), answering, clock.instant());
    connections.save(connection);
    logConnected(caller, library, profile, accountLabel, false);
  }

  /**
   * Disconnects the source of {@code library} - {@code MANAGER} on it: its grant goes at once and
   * is revoked, the connection rests until it is connected anew. Ignored without a consent.
   */
  @Transactional
  public void disconnect(CurrentUser caller, UUID libraryId) {
    KnowledgeLibrary library = managedLibrary(caller, libraryId);
    LibraryConnection connection = connections.findById(library.getId()).orElse(null);
    if (connection == null || connection.getConnectedAt() == null) {
      throw new ValidationException("Die Quelle dieser Bibliothek ist nicht verbunden.");
    }
    end(library, connection, ConnectionEndCause.SELF, ConnectionLogActor.person(caller.id()));
    connections.save(connection);
  }

  /**
   * Ends the consent {@code library} holds before it leaves its profile - moved to another one,
   * released or deleted by {@code actorUserId} ({@code null} for the system): the grant goes and is
   * revoked, the end is logged, the row forgets it.
   */
  @Transactional
  public void forget(KnowledgeLibrary library, ConnectionEndCause cause, UUID actorUserId) {
    ConnectionLogActor actor =
        actorUserId == null ? ConnectionLogActor.system() : ConnectionLogActor.person(actorUserId);
    connections
        .findById(library.getId())
        .filter(connection -> connection.getConnectedAt() != null)
        .ifPresent(
            connection -> {
              end(library, connection, cause, actor);
              connection.consentForgotten(clock.instant());
              connections.save(connection);
            });
  }

  @Override
  @Transactional
  public void consentRejected(SourceConsent owner) {
    LibraryConnection connection = connections.findById(owner.libraryId()).orElse(null);
    KnowledgeLibrary library = libraries.findById(owner.libraryId()).orElse(null);
    ConnectionProfile profile = profiles.findById(owner.profileId()).orElse(null);
    secrets.rejected(owner);
    if (connection == null || library == null || profile == null) {
      return;
    }
    connection.consentEnded(ConnectionEndCause.PROVIDER_REJECTED, clock.instant());
    connections.save(connection);
    log.record(
        library.getOrganizationId(),
        ConnectionLogEventType.EXPIRED,
        ConnectionLogActor.system(),
        ConnectionLogOwner.library(library.getId(), connection.getAccountLabel()),
        profile.getId(),
        profile.getName(),
        ConnectionEndCause.PROVIDER_REJECTED);
    tell(
        library,
        connection,
        NotificationType.SOURCE_CONNECTION_EXPIRED,
        "Quelle nicht mehr verbunden: Bibliothek „" + library.getName() + "“",
        "Der Anbieter nimmt die Zustimmung für den Zugang „"
            + profile.getName()
            + "“ nicht mehr an. Verbinden Sie die Quelle in den Bibliotheksdetails neu; bis dahin"
            + " wird der Inhalt nicht aktualisiert.");
  }

  @Override
  @Transactional
  public void endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId) {
    ConnectionProfile profile = profiles.findById(profileId).orElse(null);
    Instant now = clock.instant();
    for (LibraryConnection connection : connections.findByProfileId(profileId)) {
      if (connection.getConnectedAt() == null || connection.getEndedCause() != null) {
        continue;
      }
      KnowledgeLibrary library = libraries.findById(connection.getLibraryId()).orElse(null);
      if (library == null || library.isOwnerOnly()) {
        continue;
      }
      connection.consentEnded(cause, now);
      connections.save(connection);
      log.record(
          library.getOrganizationId(),
          eventOf(cause),
          ConnectionLogActor.person(actorUserId),
          ConnectionLogOwner.library(library.getId(), connection.getAccountLabel()),
          profileId,
          profile == null ? "" : profile.getName(),
          cause);
      tell(
          library,
          connection,
          NotificationType.SOURCE_CONNECTION_ENDED,
          "Quelle getrennt: Bibliothek „" + library.getName() + "“",
          (cause == ConnectionEndCause.PROFILE_DELETED
                  ? "Die Systemverwaltung hat den Zugang entfernt."
                  : "Die Systemverwaltung hat den Zugang geändert oder alle seine Verbindungen"
                      + " getrennt.")
              + " Verbinden Sie die Quelle in den Bibliotheksdetails neu; bis dahin wird der"
              + " Inhalt nicht aktualisiert.");
    }
  }

  /** The consent of {@code library} as its managers see it, empty for a library without one. */
  @Transactional(readOnly = true)
  public Optional<ConsentView> viewOf(KnowledgeLibrary library) {
    return connections
        .findById(library.getId())
        .filter(connection -> connection.getConnectedAt() != null)
        .map(
            connection -> {
              Responsible responsible = connection.getResponsible().orElse(null);
              Instant grantEndsAt =
                  connection.getProfileId() == null || connection.getEndedCause() != null
                      ? null
                      : secrets
                          .grantEnd(new SourceConsent(connection.getProfileId(), library.getId()))
                          .orElse(null);
              return new ConsentView(
                  connection.getAccountLabel(),
                  connection.getConnectedAt(),
                  responsible,
                  responsibles.nameOf(responsible),
                  connection.getEndedCause(),
                  connection.getEndedAt(),
                  grantEndsAt);
            });
  }

  /**
   * Tells those responsible for {@code library}'s consent that its grant ends at {@code endsAt}.
   */
  @Transactional
  public void warnOfEnd(UUID libraryId, UUID profileId, String when) {
    KnowledgeLibrary library = libraries.findById(libraryId).orElse(null);
    LibraryConnection connection = connections.findById(libraryId).orElse(null);
    ConnectionProfile profile = profiles.findById(profileId).orElse(null);
    if (library == null || connection == null || profile == null) {
      return;
    }
    tell(
        library,
        connection,
        NotificationType.SOURCE_CONNECTION_EXPIRING,
        "Verbindung der Quelle läuft ab: Bibliothek „" + library.getName() + "“",
        "Der Anbieter beendet die Zustimmung für den Zugang „"
            + profile.getName()
            + "“ am "
            + when
            + ". Verbinden Sie die Quelle vorher in den Bibliotheksdetails neu; sonst wird der"
            + " Inhalt danach nicht mehr aktualisiert.");
  }

  /** Discards the grant of {@code connection}, notes why it ended and logs the end. */
  private void end(
      KnowledgeLibrary library,
      LibraryConnection connection,
      ConnectionEndCause cause,
      ConnectionLogActor actor) {
    UUID profileId = connection.getProfileId();
    if (profileId == null) {
      return;
    }
    secrets.discard(new SourceConsent(profileId, library.getId()));
    if (connection.getEndedCause() != null) {
      return;
    }
    connection.consentEnded(cause, clock.instant());
    profiles
        .findById(profileId)
        .ifPresent(
            profile ->
                log.record(
                    library.getOrganizationId(),
                    eventOf(cause),
                    actor,
                    ConnectionLogOwner.library(library.getId(), connection.getAccountLabel()),
                    profile.getId(),
                    profile.getName(),
                    cause));
  }

  private void logConnected(
      CurrentUser caller,
      KnowledgeLibrary library,
      ConnectionProfile profile,
      String accountLabel,
      boolean reconnected) {
    log.record(
        library.getOrganizationId(),
        reconnected ? ConnectionLogEventType.RECONNECTED : ConnectionLogEventType.CONNECTED,
        ConnectionLogActor.person(caller.id()),
        ConnectionLogOwner.library(library.getId(), accountLabel),
        profile.getId(),
        profile.getName(),
        null);
    List<String> fields = List.of("sourceConnection");
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.LIBRARY_SOURCE_UPDATED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.auditName())
            .before(library.auditPayload(Map.of("changedFields", fields)))
            .after(
                library.auditPayload(
                    Map.of("changedFields", fields, "serviceAccountConfirmed", true)))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private void tell(
      KnowledgeLibrary library,
      LibraryConnection connection,
      NotificationType type,
      String title,
      String body) {
    for (UUID recipient :
        responsibles.recipients(library, connection.getResponsible().orElse(null))) {
      notifications.notify(
          library.getOrganizationId(),
          recipient,
          type,
          AuditObjectType.KNOWLEDGE_LIBRARY,
          library.getId(),
          title,
          body);
    }
  }

  /**
   * Refuses (German 400) a consent of a library on {@code profile}: the profile must sign in by
   * OAuth, admit libraries, and its connector's OAuth sign-in must admit a library as owner.
   */
  private void requireLibraryConsent(ConnectionProfile profile) {
    if (profile.isMcpServer()) {
      throw new ValidationException(
          "Über den MCP-Server „" + profile.getName() + "“ wird keine Bibliothek verbunden.");
    }
    SourceConnectorDescriptor descriptor =
        connectors.getObject().descriptor(profile.getSourceType());
    ProfileAdmission.require(
        Optional.of(profile), profile.getSourceType(), descriptor, DraftOwner.LIBRARY);
    boolean admitsLibrary =
        profile.getAuthMethod() == ConnectionAuthMethod.OAUTH
            && descriptor
                .profileDeclaration()
                .signIn(ConnectionAuthMethod.OAUTH)
                .map(SignIn::owners)
                .filter(owners -> owners.contains(ConnectionOwnership.LIBRARY))
                .isPresent();
    if (!admitsLibrary) {
      throw new ValidationException(
          "Über den Zugang „"
              + profile.getName()
              + "“ lässt sich keine Quelle mit einem Dienstkonto verbinden.");
    }
  }

  /**
   * {@code libraryId} once {@code caller} manages it: {@code 404} for one the caller does not see,
   * also a private library of another person, {@code 403} below {@code MANAGER}, {@code 400} for a
   * private library, which runs on its owner's connected account only.
   */
  private KnowledgeLibrary managedLibrary(CurrentUser caller, UUID libraryId) {
    KnowledgeLibrary library =
        libraries
            .findById(libraryId)
            .filter(found -> found.getOrganizationId().equals(caller.organizationId()))
            .filter(found -> access.canRead(found, caller.id(), caller.isSystemAdmin()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    access.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    if (library.isOwnerOnly()) {
      throw new ValidationException(
          "Eine private Bibliothek läuft über das verbundene Konto ihrer Besitzerin; ein"
              + " Dienstkonto lässt sich für sie nicht verbinden.");
    }
    return library;
  }

  private static ConnectionLogEventType eventOf(ConnectionEndCause cause) {
    return switch (cause) {
      case SELF, ADDRESS_CHANGED, REGISTRATION_CHANGED, PROFILE_CHANGED ->
          ConnectionLogEventType.DISCONNECTED;
      case EMERGENCY -> ConnectionLogEventType.EMERGENCY_DISCONNECTED;
      case ACCOUNT_DEACTIVATED, PROFILE_DELETED, LIBRARY_DELETED -> ConnectionLogEventType.DELETED;
      case PROVIDER_REJECTED, SECRET_EXPIRED -> ConnectionLogEventType.EXPIRED;
    };
  }

  /**
   * A library's consent as shown with the library: the account, when and by whom it was given - the
   * responsible with name -, why and when it ended and when its grant ends; {@code null} where
   * unknown or not applicable.
   */
  public record ConsentView(
      String accountLabel,
      Instant connectedAt,
      Responsible responsible,
      String responsibleName,
      ConnectionEndCause endedCause,
      Instant endedAt,
      Instant grantEndsAt) {

    public ConsentView {
      Objects.requireNonNull(connectedAt, "connectedAt");
    }
  }
}
