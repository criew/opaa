package io.opaa.connection.account;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.ConnectedAccountState;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
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
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.PersonConnections;
import io.opaa.connection.profile.PersonConnections.StateCounts;
import io.opaa.connection.profile.ProfileAdmission;
import io.opaa.connection.profile.SourceDraft;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.GrantRejections;
import io.opaa.connection.token.NewSecret;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.notification.NotificationService;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.HeldScopes;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A person's connected accounts (ADR-0041): connecting in two public steps - {@link
 * #requireConnectable} and {@link #established} - and one way to end a connection for every cause,
 * reached per profile ({@link #endAllUnder}), per person ({@link #endAllOf}) or by the person. Each
 * step writes the connection log, always as the person's connection, in the caller's transaction. A
 * new account needs the profile's release, an existing one only an unlocked profile.
 */
@Service
public class ConnectedAccountService implements PersonConnections, GrantRejections {

  private static final Capability RELEASE = Capability.CREATE_CONNECTOR_LIBRARY;
  private static final String ADMINISTRATION = "Systemverwaltung";

  private final ConnectedAccountRepository accounts;
  private final ConnectionProfileRepository profiles;
  private final LibraryConnectionRepository libraryConnections;
  private final ConnectionSecrets secrets;
  private final ConnectionLog log;
  private final CapabilityService capabilities;
  private final ConnectorLockService locks;
  private final EffectiveSourceSettings effective;
  private final NotificationService notifications;
  private final CredentialsEncryptor encryptor;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /** Looked up per call: the core's port depends on this class, the connectors on the core. */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  ConnectedAccountService(
      ConnectedAccountRepository accounts,
      ConnectionProfileRepository profiles,
      LibraryConnectionRepository libraryConnections,
      ConnectionSecrets secrets,
      ConnectionLog log,
      CapabilityService capabilities,
      ConnectorLockService locks,
      EffectiveSourceSettings effective,
      NotificationService notifications,
      CredentialsEncryptor encryptor,
      PlatformTransactionManager transactionManager,
      Clock clock,
      ObjectProvider<SourceConnectorRegistry> connectors) {
    this.accounts = accounts;
    this.profiles = profiles;
    this.libraryConnections = libraryConnections;
    this.secrets = secrets;
    this.log = log;
    this.capabilities = capabilities;
    this.locks = locks;
    this.effective = effective;
    this.notifications = notifications;
    this.encryptor = encryptor;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.connectors = connectors;
  }

  /**
   * Refuses connecting the caller's account on {@code profile}: {@code 400} where neither the
   * profile nor its connector admits persons, {@code 403 CONNECTOR_LOCKED} on a lock, and {@code
   * 403 CAPABILITY_REQUIRED} for a new account without the profile's release. Reconnecting an
   * existing account needs no release.
   */
  @Transactional(readOnly = true)
  public void requireConnectable(CurrentUser caller, ConnectionProfile profile) {
    ProfileAdmission.require(
        Optional.of(profile),
        profile.getSourceType(),
        descriptorOf(profile),
        SourceDraft.DraftOwner.PERSON);
    if (locks.creationLock(profile.getSourceType(), profile).isPresent()) {
      throw new AccessDeniedException(
          (profile.isLocked()
                  ? "Der Zugang „" + profile.getName() + "“ ist gesperrt."
                  : "Die Quellart des Zugangs „" + profile.getName() + "“ ist gesperrt.")
              + " Verbinden ist erst wieder möglich, wenn die Systemverwaltung die Sperre"
              + " aufhebt.",
          ConnectorLockService.CONNECTOR_LOCKED);
    }
    if (accounts.findByUserIdAndProfileId(caller.id(), profile.getId()).isEmpty()) {
      capabilities.requireCapability(
          caller,
          RELEASE,
          ConnectorScope.ofProfile(profile.getId()),
          "den Zugang „" + profile.getName() + "“");
    }
  }

  /**
   * Stores {@code secret} as the caller's connected account on {@code profile}, after {@link
   * #requireConnectable}: a new account is connected, an existing one reconnected. {@code label} is
   * the caller's account name at the provider, {@code null} for none; only the caller sees it. It
   * trusts that the secret signed in already, so only this package and the OAuth flow call it
   * ({@code aConnectionIsEstablishedOnlyAfterItsSignIn}).
   */
  @Transactional
  public AccountOverview.Account established(
      CurrentUser caller, ConnectionProfile profile, NewSecret secret, String label) {
    requireConnectable(caller, profile);
    Instant now = clock.instant();
    Optional<ConnectedAccount> existing =
        accounts.findByUserIdAndProfileId(caller.id(), profile.getId());
    ConnectedAccount account =
        existing.orElseGet(
            () -> new ConnectedAccount(caller.organizationId(), caller.id(), profile.getId(), now));
    account.connected(label == null ? null : encryptor.encrypt(label), now, existing.isPresent());
    accounts.saveAndFlush(account);
    secrets.store(
        new PersonOwned(profile.getId(), caller.id()), secret, effective.personTarget(profile));
    log.record(
        caller.organizationId(),
        existing.isPresent()
            ? ConnectionLogEventType.RECONNECTED
            : ConnectionLogEventType.CONNECTED,
        ConnectionLogActor.person(caller.id()),
        ConnectionLogOwner.person(caller.id()),
        profile.getId(),
        profile.getName(),
        null);
    return viewOf(account, profile, capabilities.scopesOf(caller, RELEASE));
  }

  /**
   * Connects or reconnects the caller's account on {@code profileId} with a personal secret, after
   * the connector signed in with it; nothing is stored when the sign-in fails ({@code 400}). The
   * sign-in runs outside a transaction.
   */
  public AccountOverview.Account connect(
      CurrentUser caller, UUID profileId, String username, String secretValue) {
    ConnectionProfile profile =
        profiles
            .findById(profileId)
            .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    requireConnectable(caller, profile);
    PersonalSecretForm form =
        descriptorOf(profile)
            .profileDeclaration()
            .signIn(profile.getAuthMethod())
            .map(SignIn::secretForm)
            .orElse(null);
    if (form == null) {
      throw new ValidationException(
          "Der Zugang „"
              + profile.getName()
              + "“ meldet sich nicht mit einem persönlichen Geheimnis an; dieses Konto lässt sich"
              + " hier noch nicht verbinden.");
    }
    String user = blankToNull(username);
    String value = secretOf(form, user, secretValue);
    signIn(profile, value);
    return transactions.execute(
        status -> established(caller, profile, NewSecret.personal(value), user));
  }

  /**
   * Disconnects the caller's account on {@code profileId} and deletes its secret at once; always
   * possible, also without release and on a locked profile.
   */
  @Transactional
  public void disconnect(CurrentUser caller, UUID profileId) {
    ConnectedAccount account =
        accounts
            .findByUserIdAndProfileId(caller.id(), profileId)
            .orElseThrow(() -> new NotFoundException("Keine Verbindung zu diesem Zugang"));
    end(account, ConnectionEndCause.SELF, ConnectionLogActor.person(caller.id()));
  }

  /**
   * The provider rejected the secret of {@code owner}, with which {@code library} was reached: the
   * connection expires, is logged as such and its owner is told. Ignored once it is not connected.
   */
  @Transactional
  public void rejected(KnowledgeLibrary library, PersonOwned owner) {
    expire(
        owner,
        AuditObjectType.KNOWLEDGE_LIBRARY,
        library.getId(),
        "Der Anbieter hat die Anmeldung Ihres verbundenen Kontos abgelehnt. Verbinden Sie es auf"
            + " der Seite „Verbundene Konten“ neu; bis dahin wird Ihre private Bibliothek nicht"
            + " aktualisiert.");
  }

  /**
   * The provider no longer takes the OAuth grant of {@code owner}: the connection expires as by
   * {@link #rejected}, in the caller's transaction, which holds the grant's row.
   */
  @Override
  @Transactional
  public void grantRejected(PersonOwned owner) {
    expire(
        owner,
        AuditObjectType.SYSTEM_SETTING,
        owner.profileId(),
        "Der Anbieter nimmt die Zustimmung Ihres verbundenen Kontos nicht mehr an. Verbinden Sie"
            + " es auf der Seite „Verbundene Konten“ neu; bis dahin wird nichts aus diesem Zugang"
            + " aktualisiert.");
  }

  /**
   * Expires the connection of {@code owner} for {@code PROVIDER_REJECTED}: logged, its owner told
   * {@code body}; ignored once it is not connected.
   */
  private void expire(PersonOwned owner, AuditObjectType objectType, UUID objectId, String body) {
    Optional<ConnectedAccount> found =
        accounts.findByUserIdAndProfileId(owner.userId(), owner.profileId());
    if (found.isEmpty() || found.get().getState() != ConnectedAccountState.CONNECTED) {
      return;
    }
    ConnectedAccount account = found.get();
    ConnectionProfile profile = profiles.findById(owner.profileId()).orElseThrow();
    secrets.rejected(owner);
    account.expired(ConnectionEndCause.PROVIDER_REJECTED, clock.instant());
    accounts.save(account);
    log.record(
        account.getOrganizationId(),
        ConnectionLogEventType.EXPIRED,
        ConnectionLogActor.system(),
        ConnectionLogOwner.person(account.getUserId()),
        profile.getId(),
        profile.getName(),
        ConnectionEndCause.PROVIDER_REJECTED);
    notifications.notify(
        account.getOrganizationId(),
        account.getUserId(),
        NotificationType.CONNECTION_EXPIRED,
        objectType,
        objectId,
        "Verbindung abgelaufen: Zugang „" + profile.getName() + "“",
        body);
  }

  /** The caller's connections, what they may connect now, and who to ask for a missing one. */
  @Transactional(readOnly = true)
  public AccountOverview overview(CurrentUser caller) {
    HeldScopes held = capabilities.scopesOf(caller, RELEASE);
    List<AccountOverview.Account> views = new ArrayList<>();
    Set<UUID> connected = new HashSet<>();
    for (ConnectedAccount account : accounts.findByUserIdOrderByConnectedAtAsc(caller.id())) {
      connected.add(account.getProfileId());
      profiles
          .findById(account.getProfileId())
          .ifPresent(profile -> views.add(viewOf(account, profile, held)));
    }
    List<AccountOverview.Connectable> connectable = new ArrayList<>();
    for (ConnectionProfile profile : profiles.findAllByOrderByNameAsc()) {
      Optional<SourceConnectorDescriptor> descriptor = findDescriptor(profile);
      if (!connected.contains(profile.getId())
          && descriptor.isPresent()
          && descriptor.get().admitsProfiles()
          && ProfileAdmission.admitsPersons(profile, descriptor.get())
          && locks.creationLock(profile.getSourceType(), profile).isEmpty()
          && held.covers(ConnectorScope.ofProfile(profile.getId()))) {
        connectable.add(
            new AccountOverview.Connectable(
                profile.getId(),
                profile.getName(),
                profile.getAuthMethod(),
                secretFormOf(profile, descriptor.get())));
      }
    }
    return new AccountOverview(
        views,
        connectable,
        new AccountOverview.MissingAccess(
            ADMINISTRATION,
            "Zugänge für verbundene Konten legt die Systemverwaltung an und gibt sie frei. Fehlt"
                + " Ihnen ein Zugang, wenden Sie sich an die Systemverwaltung."));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, StateCounts> countsAmong(Collection<UUID> profileIds) {
    Map<UUID, long[]> raw = new HashMap<>();
    for (ConnectedAccountRepository.StateCount row : accounts.countByProfileAndState(profileIds)) {
      long[] counts = raw.computeIfAbsent(row.getProfileId(), id -> new long[2]);
      switch (row.getState()) {
        case CONNECTED -> counts[0] += row.getConnections();
        case EXPIRED -> counts[1] += row.getConnections();
        case DISCONNECTED -> {}
      }
    }
    Map<UUID, StateCounts> counts = new HashMap<>();
    raw.forEach((profileId, pair) -> counts.put(profileId, new StateCounts(pair[0], pair[1])));
    return counts;
  }

  @Override
  @Transactional
  public void endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId) {
    for (ConnectedAccount account :
        accounts.findByProfileIdAndStateNot(profileId, ConnectedAccountState.DISCONNECTED)) {
      end(account, cause, ConnectionLogActor.person(actorUserId));
    }
  }

  /**
   * Ends every connection of person {@code userId} that is not disconnected for {@code cause},
   * caused by {@code actor}, with one connection-log entry each, in the caller's transaction.
   */
  @Transactional
  Ended endAllOf(UUID userId, ConnectionEndCause cause, ConnectionLogActor actor) {
    int connections = 0;
    int secretsDiscarded = 0;
    for (ConnectedAccount account :
        accounts.findByUserIdAndStateNot(userId, ConnectedAccountState.DISCONNECTED)) {
      secretsDiscarded += end(account, cause, actor);
      connections++;
    }
    return new Ended(connections, secretsDiscarded);
  }

  /**
   * After a private library of person {@code userId} was erased: each of her disconnected
   * connections that no private library runs on any more goes, logged as deleted for {@code
   * LIBRARY_DELETED}, in the caller's transaction.
   *
   * @return how many connections went
   */
  @Transactional
  public int dropDisconnectedWithoutPrivateLibrary(UUID userId) {
    int dropped = 0;
    for (ConnectedAccount account :
        accounts.findByUserIdAndState(userId, ConnectedAccountState.DISCONNECTED)) {
      if (!libraryConnections.findPrivateLibrariesOn(account.getProfileId(), userId).isEmpty()) {
        continue;
      }
      profiles
          .findById(account.getProfileId())
          .ifPresent(
              profile ->
                  log.record(
                      account.getOrganizationId(),
                      eventOf(ConnectionEndCause.LIBRARY_DELETED),
                      ConnectionLogActor.system(),
                      ConnectionLogOwner.person(userId),
                      profile.getId(),
                      profile.getName(),
                      ConnectionEndCause.LIBRARY_DELETED));
      accounts.delete(account);
      dropped++;
    }
    return dropped;
  }

  /**
   * The one way a connection ends: its secret goes at once, the end is logged, an end the person
   * did not cause is told them, and the row stays as {@code DISCONNECTED} only while a private
   * library of the person runs on it. Returns how many stored secrets went.
   */
  private int end(ConnectedAccount account, ConnectionEndCause cause, ConnectionLogActor actor) {
    int discarded = 0;
    boolean feedsPrivateLibrary =
        !libraryConnections
            .findPrivateLibrariesOn(account.getProfileId(), account.getUserId())
            .isEmpty();
    if (account.getState() != ConnectedAccountState.DISCONNECTED) {
      ConnectionProfile profile = profiles.findById(account.getProfileId()).orElseThrow();
      discarded = secrets.discard(new PersonOwned(account.getProfileId(), account.getUserId()));
      log.record(
          account.getOrganizationId(),
          eventOf(cause),
          actor,
          ConnectionLogOwner.person(account.getUserId()),
          profile.getId(),
          profile.getName(),
          cause);
      endNotice(cause, profile, feedsPrivateLibrary)
          .ifPresent(
              body ->
                  notifications.notify(
                      account.getOrganizationId(),
                      account.getUserId(),
                      NotificationType.CONNECTION_ENDED,
                      AuditObjectType.SYSTEM_SETTING,
                      profile.getId(),
                      "Verbindung getrennt: Zugang „" + profile.getName() + "“",
                      body));
    }
    if (!feedsPrivateLibrary) {
      accounts.delete(account);
    } else {
      account.disconnected(cause);
      accounts.save(account);
    }
    return discarded;
  }

  /**
   * What the person learns of an end the administration caused; empty for one they caused, for an
   * expiry, which tells them itself, and for a deactivated account, which reads nothing. A private
   * library is mentioned only where one runs on the connection.
   */
  private static Optional<String> endNotice(
      ConnectionEndCause cause, ConnectionProfile profile, boolean feedsPrivateLibrary) {
    String reconnect =
        feedsPrivateLibrary
            ? " Verbinden Sie Ihr Konto auf der Seite „Verbundene Konten“ neu; bis dahin wird Ihre"
                + " private Bibliothek nicht aktualisiert."
            : " Sie können Ihr Konto auf der Seite „Verbundene Konten“ neu verbinden.";
    String gone =
        feedsPrivateLibrary ? " Ihre private Bibliothek wird nicht mehr aktualisiert." : "";
    return switch (cause) {
      case ADDRESS_CHANGED, REGISTRATION_CHANGED, PROFILE_CHANGED ->
          Optional.of(
              profile.getOwnership().admitsPersons()
                  ? "Die Systemverwaltung hat den Zugang geändert; Ihre Zugangsdaten gelten"
                      + " dafür nicht mehr."
                      + reconnect
                  : "Die Systemverwaltung hat den Zugang für verbundene Konten von Personen"
                      + " geschlossen."
                      + gone);
      case EMERGENCY ->
          Optional.of(
              "Die Systemverwaltung hat alle Verbindungen dieses Zugangs getrennt." + reconnect);
      case PROFILE_DELETED -> Optional.of("Die Systemverwaltung hat den Zugang entfernt." + gone);
      case SELF, ACCOUNT_DEACTIVATED, LIBRARY_DELETED, PROVIDER_REJECTED, SECRET_EXPIRED ->
          Optional.empty();
    };
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

  private AccountOverview.Account viewOf(
      ConnectedAccount account, ConnectionProfile profile, HeldScopes held) {
    boolean released = held.covers(ConnectorScope.ofProfile(profile.getId()));
    boolean locked = locks.creationLock(profile.getSourceType(), profile).isPresent();
    String notice;
    String responsible = null;
    if (locked) {
      notice =
          "Der Zugang ist gesperrt; neu verbinden ist erst nach Aufhebung der Sperre möglich,"
              + " Trennen geht immer.";
      responsible = ADMINISTRATION;
    } else if (!released) {
      notice =
          "Nicht mehr freigegeben – die Verbindung läuft weiter und lässt sich trennen und neu"
              + " verbinden; ein neues Konto ist nicht mehr möglich.";
      responsible = ADMINISTRATION;
    } else {
      notice =
          switch (account.getState()) {
            case CONNECTED -> null;
            case EXPIRED ->
                "Abgelaufen – bitte neu verbinden. Bis dahin wird der Inhalt nicht"
                    + " aktualisiert.";
            case DISCONNECTED ->
                "Getrennt – Ihre private Bibliothek ruht, bis Sie das Konto neu verbinden.";
          };
    }
    return new AccountOverview.Account(
        profile.getId(),
        profile.getName(),
        profile.getAuthMethod(),
        findDescriptor(profile).map(found -> secretFormOf(profile, found)).orElse(null),
        account.getState(),
        labelOf(account),
        released,
        !locked,
        notice,
        responsible,
        account.getConnectedAt(),
        account.getReconnectedAt(),
        account.getState() == ConnectedAccountState.CONNECTED
            ? secrets.grantEnd(new PersonOwned(profile.getId(), account.getUserId())).orElse(null)
            : null,
        libraryConnections.findPrivateLibrariesOn(profile.getId(), account.getUserId()).stream()
            .map(library -> new AccountOverview.Library(library.getId(), library.getName()))
            .toList());
  }

  /** The account name for its owner; an unreadable one is shown as none. */
  private String labelOf(ConnectedAccount account) {
    String ciphertext = account.getAccountLabelCiphertext();
    if (ciphertext == null) {
      return null;
    }
    try {
      return encryptor.decrypt(ciphertext);
    } catch (CredentialsEncryptionKeyMissingException e) {
      return null;
    }
  }

  /**
   * Signs in at the profile's server with {@code value} as the connector would for the person.
   *
   * @throws ValidationException (German 400) with the connector's message when it fails
   */
  private void signIn(ConnectionProfile profile, String value) {
    SourceConnector connector = connectors.getObject().connector(profile.getSourceType());
    SourceSettings settings;
    try {
      settings =
          effective.ofDraft(
              SourceDraft.ofPerson(
                  profile.getSourceType(),
                  profile.getId(),
                  new SourceSettings(null, null, null, value, false, null)));
    } catch (SourceCredentialsException e) {
      throw new ValidationException(e.getMessage());
    }
    SourceConnectionTestResult result = connector.testConnection(settings, null);
    if (!result.reachable() || Boolean.FALSE.equals(result.credentialsVerified())) {
      throw new ValidationException(
          result.message() == null || result.message().isBlank()
              ? "Die Anmeldung beim Zugang „" + profile.getName() + "“ ist fehlgeschlagen."
              : result.message());
    }
  }

  /** The stored form of the secret: {@code user:secret}, or the token alone. */
  private static String secretOf(PersonalSecretForm form, String user, String secretValue) {
    if (secretValue == null || secretValue.isBlank()) {
      throw new ValidationException("secret ist erforderlich");
    }
    String secret = secretValue;
    return switch (form) {
      case TOKEN -> {
        if (user != null) {
          throw new ValidationException("Dieser Zugang nimmt nur ein Token, keinen Benutzernamen");
        }
        yield secret;
      }
      case USERNAME_AND_PASSWORD -> {
        if (user == null) {
          throw new ValidationException("username ist erforderlich");
        }
        if (user.contains(":")) {
          throw new ValidationException("username darf keinen Doppelpunkt enthalten");
        }
        yield user + ":" + secret;
      }
    };
  }

  private static PersonalSecretForm secretFormOf(
      ConnectionProfile profile, SourceConnectorDescriptor descriptor) {
    return descriptor
        .profileDeclaration()
        .signIn(profile.getAuthMethod())
        .map(SignIn::secretForm)
        .orElse(null);
  }

  private SourceConnectorDescriptor descriptorOf(ConnectionProfile profile) {
    return connectors.getObject().descriptor(profile.getSourceType());
  }

  private Optional<SourceConnectorDescriptor> findDescriptor(ConnectionProfile profile) {
    return connectors.getObject().find(profile.getSourceType()).map(SourceConnector::descriptor);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /** How many connections ended and how many stored secrets went with them; for the log only. */
  record Ended(int connections, int secrets) {}
}
