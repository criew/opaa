package io.opaa.connection.profile;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.Capability;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.SourceTransitions.Move;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.indexing.source.ClientCredentialsAuth;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.DefaultKey;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.ServiceAccountKey;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceChangeGate.Answers;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.permission.CapabilityService;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, changes and deletes connection profiles - system administration only, enforced by the
 * caller. A changed server address or app registration discards every secret held under the profile
 * - of libraries and of persons, whose connections end through {@link PersonConnections}; a new
 * client secret alone discards nothing. A changed default only the profile sets (Google Drive's
 * imitated account) discards the run state of every library on it and notifies their managers. What
 * a change discards ({@link Discards}) is confirmed first; private libraries are neither counted
 * nor waited for. The client secret - a service account key is checked here and names the client id
 * - is encrypted here and never returned; the audit names fields and library counts.
 */
@Service
@Transactional(readOnly = true)
public class ConnectionProfileService {

  /** Refusal of a change that would discard secrets without the caller's confirmation. */
  public static final String CONFIRMATION_REQUIRED = "CONNECTION_PROFILE_CONFIRMATION_REQUIRED";

  /** How many days before its expiry a client secret is reported as expiring. */
  public static final int SECRET_EXPIRY_WARNING_DAYS = 14;

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_FIELD_LENGTH = 255;
  private static final int MAX_SCOPES_LENGTH = 2000;
  private static final int MAX_URL_LENGTH = 2000;

  private final ConnectionProfileRepository profiles;
  private final LibraryConnectionRepository connections;
  private final KnowledgeLibraryRepository libraries;
  private final SourceConnectorRegistry connectors;
  private final ConnectionSecrets secrets;
  private final PersonConnections persons;
  private final SourceConsentEnds consents;
  private final PersonNumbers personNumbers;
  private final SourceTransitions transitions;
  private final CredentialsEncryptor encryptor;
  private final AuditEventRecorder audit;
  private final CapabilityService capabilities;
  private final PrivateLibraryRelease privateRelease;
  private final ProfileFullSync fullSync;
  private final Clock clock;

  public ConnectionProfileService(
      ConnectionProfileRepository profiles,
      LibraryConnectionRepository connections,
      KnowledgeLibraryRepository libraries,
      SourceConnectorRegistry connectors,
      ConnectionSecrets secrets,
      PersonConnections persons,
      SourceConsentEnds consents,
      PersonNumbers personNumbers,
      SourceTransitions transitions,
      CredentialsEncryptor encryptor,
      AuditEventRecorder audit,
      CapabilityService capabilities,
      PrivateLibraryRelease privateRelease,
      ProfileFullSync fullSync,
      Clock clock) {
    this.privateRelease = privateRelease;
    this.profiles = profiles;
    this.connections = connections;
    this.libraries = libraries;
    this.connectors = connectors;
    this.secrets = secrets;
    this.persons = persons;
    this.consents = consents;
    this.personNumbers = personNumbers;
    this.transitions = transitions;
    this.encryptor = encryptor;
    this.audit = audit;
    this.capabilities = capabilities;
    this.fullSync = fullSync;
    this.clock = clock;
  }

  public List<ConnectionProfile> list() {
    return profiles.findConnectorsByName();
  }

  /** The profiles a library of {@code sourceType} may be connected through. */
  public List<ConnectionProfile> selectableFor(SourceType sourceType) {
    return profiles.findBySourceTypeOrderByNameAsc(sourceType).stream()
        .filter(profile -> profile.getOwnership().admitsLibraries())
        .toList();
  }

  /** The ones of {@code ids} that name an existing profile. */
  @Transactional(readOnly = true)
  public Set<UUID> existingAmong(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return profiles.findAllById(ids).stream()
        .filter(profile -> !profile.isMcpServer())
        .map(ConnectionProfile::getId)
        .collect(Collectors.toSet());
  }

  /** The connector profile {@code id}; an MCP server is not found here. */
  public ConnectionProfile get(UUID id) {
    return profiles
        .findById(id)
        .filter(found -> !found.isMcpServer())
        .orElseThrow(ConnectionProfileService::notFound);
  }

  /** What a change of address or registration, a shutdown or a deletion would cut off. */
  public ProfileImpact impact(UUID id) {
    get(id);
    long libraryConnections = connections.countSharedByProfileId(id);
    return new ProfileImpact(
        libraryConnections,
        libraryConnections,
        personNumbers.totalOf(id),
        List.of(),
        null,
        Discards.NONE);
  }

  /** The connections of each of {@code profiles}, with one query for all of them. */
  public Map<UUID, Long> connectionCounts(List<ConnectionProfile> profiles) {
    Map<UUID, Long> counts = new LinkedHashMap<>();
    if (profiles.isEmpty()) {
      return counts;
    }
    for (LibraryConnectionRepository.ProfileConnectionCount row :
        connections.countSharedByProfileIdIn(
            profiles.stream().map(ConnectionProfile::getId).toList())) {
      counts.put(row.getProfileId(), row.getConnections());
    }
    return counts;
  }

  /** Whether the client secret of {@code profile} expires within the warning period. */
  public boolean secretExpiresSoon(ConnectionProfile profile) {
    LocalDate expiresOn = profile.getClientSecretExpiresOn();
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return expiresOn != null && !expiresOn.isAfter(today.plusDays(SECRET_EXPIRY_WARNING_DAYS));
  }

  @Transactional
  public ConnectionProfile create(
      CurrentUser caller, SourceType sourceType, ConnectionProfileValues values, String secret) {
    SourceConnector connector = connectorAdmittingProfiles(sourceType);
    Keyed keyed = keyed(values, secret, null);
    ConnectionProfileValues validated = validate(connector, keyed.values(), null);
    requireSecretFits(validated.authMethod(), keyed.secret());
    Instant now = clock.instant();
    ConnectionProfile profile = new ConnectionProfile(sourceType, now);
    profile.replace(validated, encryptor.encrypt(blankToNull(keyed.secret())), now);
    profiles.save(profile);
    record(caller, AuditEventType.CONNECTION_PROFILE_CREATED, profile, null, auditState(profile));
    return profile;
  }

  /**
   * What a proposed change of profile {@code id} affects, with every connector refusal; nothing is
   * written, and the connectors are asked outside any transaction. {@code secret} counts only as
   * far as a new service account key names another client id.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ProfileImpact preview(UUID id, ConnectionProfileValues values, String secret) {
    ConnectionProfile profile = get(id);
    ProfileChange change = plan(profile, keyed(values, secret, profile).values());
    PrivateLibraryRelease.Verdict verdict =
        PrivateLibraryRelease.judge(
            transitions.check(change.moves(), new Answers()), change.privateLibraries());
    return new ProfileImpact(
        change.connections(),
        change.connections(),
        personNumbers.totalOf(id),
        verdict.vetoes(),
        change.forPersons() ? privateReleases(change, change.released(verdict)) : null,
        discardsOf(change, id));
  }

  /**
   * Asks the connector of every library a proposed change of profile {@code id} alters, outside any
   * transaction, so their costly checks run before the write; {@link #update} takes the answers and
   * asks only for what changed meanwhile. A change still needing {@code confirmed} is refused
   * first, before any connector is asked.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Answers check(UUID id, ConnectionProfileValues values, String secret, boolean confirmed) {
    ConnectionProfile profile = get(id);
    ProfileChange change = plan(profile, keyed(values, secret, profile).values());
    if (!confirmed) {
      requireConfirmed(discardsOf(change, id));
    }
    requireNoneRunning(change);
    Answers answers = new Answers();
    transitions.check(change.moves(), answers);
    return answers;
  }

  /** {@link #update} asking every connector within the write. */
  @Transactional
  public ConnectionProfile update(
      CurrentUser caller,
      UUID id,
      ConnectionProfileValues values,
      String secret,
      boolean confirmed) {
    return update(caller, id, values, secret, confirmed, new Answers());
  }

  /**
   * Replaces every editable field; {@code secret} {@code null} keeps the stored one, blank clears
   * it, anything else replaces it and lifts a rejection of the profile's own sign-in. A new
   * address, client id (for a service account key: another account in the key), tenant, scope list,
   * endpoint or sign-in method discards every secret held under the profile - revoked with the
   * registration before the change - and ends the persons' connections, a new binding the secrets
   * it concerns, a changed default only the profile sets the run state of every library on it -
   * each refused with 409 {@value #CONFIRMATION_REQUIRED} while there are such and {@code
   * confirmed} is false. Every library whose effective configuration changes passes its connector
   * first ({@code answers} given by {@link #check}); one refusal leaves profile and libraries
   * unchanged (400 {@value ChangeRejection#PROFILE_CHANGE_REJECTED}).
   */
  @Transactional
  public ConnectionProfile update(
      CurrentUser caller,
      UUID id,
      ConnectionProfileValues values,
      String secret,
      boolean confirmed,
      Answers answers) {
    profiles.lockForChange(id);
    ConnectionProfile profile = get(id);
    Keyed keyed = keyed(values, secret, profile);
    ProfileChange change = plan(profile, keyed.values());
    ConnectionProfileValues validated = change.values();
    String newSecret = blankToNull(keyed.secret());
    requireSecretFits(validated.authMethod(), newSecret);
    String ciphertext;
    boolean keyChangesMeaning =
        (validated.authMethod() == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY)
            != (profile.getAuthMethod() == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY);
    if (!validated.authMethod().usesAppRegistration()) {
      ciphertext = null;
    } else if (secret == null) {
      // a client secret is no key file, and a key file no client secret
      ciphertext = keyChangesMeaning ? null : profile.getClientSecretCiphertext();
    } else {
      ciphertext = encryptor.encrypt(newSecret);
    }
    if (!confirmed) {
      requireConfirmed(discardsOf(change, id));
    }
    requireNoneRunning(change);
    Set<UUID> discarding = change.discarding();
    long affected = change.affected();
    PrivateLibraryRelease.Verdict verdict =
        PrivateLibraryRelease.judge(
            transitions.check(change.moves(), answers), change.privateLibraries());
    if (!verdict.vetoes().isEmpty()) {
      throw ChangeRejection.refusingProfileChange(verdict.vetoes());
    }
    Set<UUID> released = change.released(verdict);
    privateRelease.release(caller, profile, released);
    Map<String, Object> before = auditState(profile);
    ConnectionEndCause cause =
        change.addressChanged()
            ? ConnectionEndCause.ADDRESS_CHANGED
            : ConnectionEndCause.REGISTRATION_CHANGED;
    if (change.discardsAll()) {
      // before the change: a token is revoked with the registration it was issued to
      secrets.discardAllUnder(id, cause);
    }
    profile.replace(validated, ciphertext, clock.instant());
    if (secret != null || change.discardsAll()) {
      profile.signInRejectedSince(null);
    }
    profiles.save(profile);
    for (Move move : change.moves()) {
      KnowledgeLibrary library = move.library();
      if (released.contains(library.getId())) {
        continue;
      }
      if (!Objects.equals(library.getSourceUrl(), move.after().sourceUrl())) {
        library.moveSourceUrl(move.after().sourceUrl());
      }
      if (discarding.contains(library.getId())) {
        library.dropSourceCredentials();
        if (!change.discardsAll()) {
          // only the library's own secret: a person's is shared by every library on the account
          secrets.discard(new LibraryOwned(library.getId()));
        }
      }
      transitions.keepDefaults(move);
      libraries.save(library);
    }
    if (change.discardsAll()) {
      persons.endAllUnder(id, cause, caller.id());
      consents.endAllUnder(id, cause, caller.id());
    } else if (change.dropsPersons() || change.rebindsPersons()) {
      persons.endAllUnder(id, ConnectionEndCause.PROFILE_CHANGED, caller.id());
    }
    for (Move move : change.moves()) {
      KnowledgeLibrary library = move.library();
      if (released.contains(library.getId())) {
        continue;
      }
      Set<String> changed = transitions.applied(move);
      transitions.record(caller, library, changed);
      if (change.resetsRunState() && change.privateLibraries().contains(library.getId())) {
        fullSync.repeatAfterGoingRun(
            library, !Objects.equals(move.before().sourceUrl(), move.after().sourceUrl()), changed);
      }
    }
    if (change.resetsRunState()) {
      fullSync.notifyManagers(
          profile,
          String.join(", ", change.fullSyncLabels()),
          change.moves().stream()
              .map(Move::library)
              .filter(library -> !released.contains(library.getId()))
              .toList());
    }
    Map<String, Object> after = auditState(profile);
    if (affected > 0) {
      after.put("connectionsDiscarded", affected);
    }
    record(caller, AuditEventType.CONNECTION_PROFILE_CHANGED, profile, before, after);
    return profile;
  }

  /**
   * How many private libraries the change releases from the profile, masked by their owners in each
   * of their organizations.
   */
  private PersonCount privateReleases(ProfileChange change, Set<UUID> released) {
    List<KnowledgeLibrary> leaving =
        change.moves().stream()
            .map(Move::library)
            .filter(library -> released.contains(library.getId()))
            .toList();
    return privateRelease.count(leaving, personNumbers);
  }

  /**
   * What saving {@code change} of profile {@code id} discards; the persons' connected accounts are
   * named on every profile for persons whose change ends them, whether or not one is connected.
   */
  private Discards discardsOf(ProfileChange change, UUID id) {
    return new Discards(
        change.discardsAll() ? change.connections() : 0,
        change.secretsDiscarded(),
        change.configurationsChanged(),
        change.personsConcerned() ? personNumbers.totalOf(id) : null,
        change.fullSyncLabels(),
        change.fullSyncs());
  }

  /** Refuses with 409 a change that discards anything without the caller's confirmation. */
  private static void requireConfirmed(Discards discards) {
    String question = discards.confirmation();
    if (question != null) {
      throw new ConflictException(question, CONFIRMATION_REQUIRED);
    }
  }

  /**
   * Refuses {@code change} with 409 while it resets a shared library whose run is going; a private
   * one never holds it up, so the refusal tells nothing of it.
   */
  private void requireNoneRunning(ProfileChange change) {
    if (change.fullSyncs() > 0) {
      fullSync.requireNoneRunning(change.sharedLibraries());
    }
  }

  /**
   * {@code values} and {@code secret} as stored: for a service account key the key in its stored
   * form and the client id it names - a kept key keeps the stored id, a cleared one leaves none.
   *
   * @throws ValidationException (German 400) for a key file that cannot be read
   */
  private static Keyed keyed(
      ConnectionProfileValues values, String secret, ConnectionProfile existing) {
    if (values.authMethod() != ConnectionAuthMethod.SERVICE_ACCOUNT_KEY) {
      return new Keyed(values, secret);
    }
    String sent = blankToNull(secret);
    if (sent != null) {
      ServiceAccountKey key;
      try {
        key = ServiceAccountKey.parse(sent);
      } catch (ValidationException e) {
        throw new ValidationException(
            "clientSecret: Der Dienstkonto-Schlüssel ist keine gültige JSON-Schlüsseldatei eines"
                + " Dienstkontos");
      }
      return new Keyed(values.withClientId(key.clientEmail()), key.storedForm());
    }
    boolean keeps =
        secret == null
            && existing != null
            && existing.getAuthMethod() == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY;
    return new Keyed(values.withClientId(keeps ? existing.getClientId() : null), secret);
  }

  /**
   * The change of {@code profile} to {@code values}: the values as validated and, per connected
   * library, its move to the changed profile - an address under the old server address moves under
   * the new one, every other stays. Libraries and their secret columns are read with one query
   * each.
   */
  private ProfileChange plan(ConnectionProfile profile, ConnectionProfileValues values) {
    SourceConnector connector = connectorAdmittingProfiles(profile.getSourceType());
    ConnectionProfileValues validated = validate(connector, values, profile.getId());
    ConnectorData defaultsBefore = ConnectorData.fromJson(profile.getConnectorSettings());
    List<String> fullSyncLabels =
        connector.descriptor().profileDeclaration().defaults().keys().stream()
            .filter(DefaultKey::profileOnly)
            .filter(
                key ->
                    !Objects.equals(
                        valueOf(defaultsBefore, key), valueOf(validated.connectorSettings(), key)))
            .map(DefaultKey::label)
            .toList();
    boolean addressChanged = !profile.getServerUrl().equals(validated.serverUrl());
    boolean registrationChanged =
        profile.getAuthMethod() != validated.authMethod()
            || !Objects.equals(profile.getClientId(), validated.clientId())
            || !Objects.equals(profile.getTenant(), validated.tenant())
            || !Objects.equals(profile.getScopes(), validated.scopes())
            || !profile.getEndpoints().equals(validated.endpoints());
    ConnectionProfile candidate = profile.candidate(validated);
    List<LibraryConnection> connected = connections.findByProfileId(profile.getId());
    List<KnowledgeLibrary> found =
        connected.isEmpty()
            ? List.of()
            : libraries.findAllById(
                connected.stream().map(LibraryConnection::getLibraryId).toList());
    Set<UUID> privateLibraries =
        found.stream()
            .filter(KnowledgeLibrary::isOwnerOnly)
            .map(KnowledgeLibrary::getId)
            .collect(Collectors.toSet());
    Set<UUID> holding = transitions.holdingSecrets(found, profile.getId());
    List<Move> moves = new ArrayList<>();
    for (KnowledgeLibrary library : found) {
      String address = library.getSourceUrl();
      if (addressChanged && ServerAddress.covers(profile.getServerUrl(), address)) {
        address = ServerAddress.rebase(address, profile.getServerUrl(), validated.serverUrl());
      }
      moves.add(
          transitions.move(
              library,
              Optional.of(profile),
              Optional.of(candidate),
              address,
              addressChanged || registrationChanged,
              holding.contains(library.getId())));
    }
    boolean dropsPersons =
        profile.getOwnership().admitsPersons() && !validated.ownership().admitsPersons();
    boolean rebindsPersons =
        profile.getOwnership().admitsPersons()
            && !dropsPersons
            && transitions.rebindsPersons(profile, candidate);
    return new ProfileChange(
        validated,
        addressChanged,
        registrationChanged,
        dropsPersons,
        rebindsPersons,
        profile.getOwnership().admitsPersons(),
        connected.size() - privateLibraries.size(),
        moves,
        privateLibraries,
        fullSyncLabels);
  }

  private static Object valueOf(ConnectorData defaults, DefaultKey key) {
    return defaults == null ? null : defaults.get(key.key());
  }

  /**
   * The emergency shutdown "Alle Verbindungen trennen": discards every secret, keeps the profile.
   * For the profile's own sign-in that is its client secret or key, the one secret its libraries
   * are reached with.
   */
  @Transactional
  public ProfileImpact disconnectAll(CurrentUser caller, UUID id) {
    profiles.lockForChange(id);
    return disconnectAll(caller, get(id));
  }

  /** {@link #disconnectAll(CurrentUser, UUID)} of a profile of any kind its caller locked. */
  @Transactional
  ProfileImpact disconnectAll(CurrentUser caller, ConnectionProfile profile) {
    UUID id = profile.getId();
    PersonCount ended = personNumbers.totalOf(id);
    boolean dropsOwnSecret = signsInItself(profile.getAuthMethod()) && profile.isClientSecretSet();
    // before the secret goes: a token is revoked with the registration it was issued to
    long shared = connections.countSharedByProfileId(id);
    secrets.discardAllUnder(id, ConnectionEndCause.EMERGENCY);
    if (dropsOwnSecret) {
      profile.dropClientSecret(clock.instant());
      profiles.save(profile);
    }
    persons.endAllUnder(id, ConnectionEndCause.EMERGENCY, caller.id());
    consents.endAllUnder(id, ConnectionEndCause.EMERGENCY, caller.id());
    record(
        caller,
        AuditEventType.CONNECTION_PROFILE_DISCONNECTED,
        profile,
        null,
        Map.of("connectionsDisconnected", shared, "clientSecretDeleted", dropsOwnSecret));
    return new ProfileImpact(shared, shared, ended, List.of(), null, Discards.NONE);
  }

  private static boolean signsInItself(ConnectionAuthMethod method) {
    return method == ConnectionAuthMethod.CLIENT_CREDENTIALS
        || method == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY;
  }

  /**
   * Deletes the profile. Its connections lose their secrets and stay without a profile ("Zugang
   * entfernt"); their libraries keep their content and run no more. Its releases are withdrawn, so
   * a profile is never released before it exists.
   */
  @Transactional
  public void delete(CurrentUser caller, UUID id) {
    profiles.lockForDeletion(id);
    delete(caller, get(id));
  }

  /** {@link #delete(CurrentUser, UUID)} of a profile of any kind its caller locked for deletion. */
  @Transactional
  void delete(CurrentUser caller, ConnectionProfile profile) {
    UUID id = profile.getId();
    List<LibraryConnection> affected = connections.findByProfileId(id);
    long shared = connections.countSharedByProfileId(id);
    secrets.discardAllUnder(id, ConnectionEndCause.PROFILE_DELETED);
    persons.endAllUnder(id, ConnectionEndCause.PROFILE_DELETED, caller.id());
    consents.endAllUnder(id, ConnectionEndCause.PROFILE_DELETED, caller.id());
    Instant now = clock.instant();
    for (LibraryConnection connection : affected) {
      connection.moveTo(null, now);
    }
    connections.saveAll(affected);
    Map<String, Object> before = auditState(profile);
    before.put("connectionsRemoved", shared);
    capabilities.revokeScope(
        Capability.CREATE_CONNECTOR_LIBRARY, ConnectorScope.ofProfile(id), caller);
    profiles.delete(profile);
    record(caller, AuditEventType.CONNECTION_PROFILE_DELETED, profile, before, null);
  }

  private SourceConnector connectorAdmittingProfiles(SourceType sourceType) {
    if (sourceType == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    SourceConnector connector = connectors.connector(sourceType);
    if (!connector.descriptor().admitsProfiles()) {
      throw new ValidationException(
          "Für die Quellart " + connector.descriptor().displayName() + " gibt es keine Zugänge");
    }
    return connector;
  }

  /** The declaration's checks, then the connector's own check of the defaults' values. */
  private ConnectionProfileValues validate(
      SourceConnector connector, ConnectionProfileValues values, UUID existingId) {
    SourceConnectorDescriptor descriptor = connector.descriptor();
    ConnectionProfileValues validated =
        validate(descriptor.profileDeclaration(), descriptor.displayName(), values, existingId);
    return validated.connectorSettings() == null
        ? validated
        : validated.withConnectorSettings(
            connector.readProfileDefaults(validated.connectorSettings()));
  }

  /**
   * Checks {@code values} against {@code declaration} alone - name, address, sign-in, ownership,
   * app registration and defaults - so a profile is checkable without a connector.
   */
  ConnectionProfileValues validate(
      ProfileDeclaration declaration,
      String typeLabel,
      ConnectionProfileValues values,
      UUID existingId) {
    String name = required(values.name(), "name", MAX_NAME_LENGTH);
    if (profiles.existsByNameIgnoringCase(name, existingId)) {
      throw new ConflictException("Es gibt bereits einen Zugang mit dem Namen " + name);
    }
    String serverUrl = ServerAddress.normalize(values.serverUrl(), declaration.address());
    if (serverUrl.length() > MAX_URL_LENGTH) {
      throw new ValidationException("serverUrl ist zu lang");
    }
    ConnectionAuthMethod method = values.authMethod();
    SignIn signIn =
        (method == null ? Optional.<SignIn>empty() : declaration.signIn(method))
            .orElseThrow(
                () ->
                    new ValidationException(
                        "Die Anmeldeart wird von der Quellart " + typeLabel + " nicht angeboten"));
    if (values.ownership() == null) {
      throw new ValidationException("ownership ist erforderlich");
    }
    if (!signIn.admits(values.ownership())) {
      throw new ValidationException(
          "Die Besitzart ist für diese Anmeldeart der Quellart " + typeLabel + " nicht vorgesehen");
    }
    String clientId = optional(values.clientId(), "clientId", MAX_FIELD_LENGTH);
    String tenant = optional(values.tenant(), "tenant", MAX_FIELD_LENGTH);
    String scopes = optional(values.scopes(), "scopes", MAX_SCOPES_LENGTH);
    if (method.usesAppRegistration()) {
      if (clientId == null && method != ConnectionAuthMethod.SERVICE_ACCOUNT_KEY) {
        throw new ValidationException("clientId ist für diese Anmeldeart erforderlich");
      }
    } else if (clientId != null || tenant != null || values.clientSecretExpiresOn() != null) {
      throw new ValidationException(
          "Client-ID, Mandant und Ablaufdatum gehören nur zu einer Anmeldeart mit"
              + " App-Registrierung");
    }
    if (scopes != null && !method.usesScopes()) {
      throw new ValidationException("Scopes gehören nur zu OAuth und Client-Credentials");
    }
    if (signIn.details() instanceof ClientCredentialsAuth auth
        && auth.token().needsTenant()
        && (tenant == null || !Endpoint.WithTenant.TENANT.matcher(tenant).matches())) {
      throw new ValidationException(
          "tenant ist für diese Anmeldeart erforderlich und besteht nur aus Buchstaben, Ziffern,"
              + " Punkt und Bindestrich");
    }
    ProfileEndpoints endpoints = endpointsOf(signIn, values.endpoints());
    ConnectorData settings = declaration.defaults().read(values.connectorSettings());
    String proxy = proxyOf(values.sourceProxy());
    if ((proxy != null || values.sourceInsecureSsl()) && !reachedOverHttp(serverUrl)) {
      throw new ValidationException(
          "Proxy und Zertifikatsprüfung gelten nur für eine Server-Adresse mit http:// oder"
              + " https://");
    }
    return new ConnectionProfileValues(
        name,
        serverUrl,
        method,
        values.ownership(),
        clientId,
        values.clientSecretExpiresOn(),
        tenant,
        scopes,
        settings,
        proxy,
        values.sourceInsecureSsl(),
        endpoints);
  }

  /**
   * The endpoints {@code given} for {@code signIn}: each one its sign-in leaves to the profile is
   * required, an absolute {@code http(s)} address without user info or fragment; any other is a
   * 400.
   */
  private static ProfileEndpoints endpointsOf(SignIn signIn, ProfileEndpoints given) {
    boolean authorization = false;
    boolean token = false;
    boolean revocation = false;
    if (signIn.details() instanceof OAuthAuth auth) {
      authorization = auth.authorization() instanceof Endpoint.FromProfile;
      token = auth.token() instanceof Endpoint.FromProfile;
      revocation = auth.revocation().endpoint() instanceof Endpoint.FromProfile;
    } else if (signIn.details() instanceof ClientCredentialsAuth auth) {
      token = auth.token() instanceof Endpoint.FromProfile;
    }
    return new ProfileEndpoints(
        endpoint(given.authorization(), authorization, "authorizationEndpoint"),
        endpoint(given.token(), token, "tokenEndpoint"),
        endpoint(given.revocation(), revocation, "revocationEndpoint"));
  }

  private static String endpoint(String value, boolean fromProfile, String field) {
    String trimmed = optional(value, field, MAX_URL_LENGTH);
    if (!fromProfile) {
      if (trimmed != null) {
        throw new ValidationException(
            field + " gehört nicht zu dieser Anmeldeart der Quellart; ihr Endpunkt ist fest");
      }
      return null;
    }
    if (trimmed == null) {
      throw new ValidationException(field + " ist für diese Anmeldeart erforderlich");
    }
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new ValidationException(field + " ist keine gültige Adresse");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("https") || scheme.equals("http"))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null) {
      throw new ValidationException(
          field
              + " muss eine vollständige Adresse mit https:// oder http:// sein, ohne"
              + " Benutzerangabe und Fragment");
    }
    return trimmed;
  }

  /** Whether the normalised {@code serverUrl} is reached over HTTP, where proxy and TLS apply. */
  private static boolean reachedOverHttp(String serverUrl) {
    return serverUrl.startsWith("https://") || serverUrl.startsWith("http://");
  }

  /** A proxy in {@code host:port} form, {@code null} for none. */
  private static String proxyOf(String value) {
    String proxy = optional(value, "sourceProxy", MAX_FIELD_LENGTH);
    if (proxy == null) {
      return null;
    }
    try {
      ProxyAndCredentials parsed = ProxyAndCredentials.parse(proxy, null);
      if (parsed.proxyHost() == null || proxy.contains("@")) {
        throw new ValidationException(ProxyAndCredentials.INVALID_PROXY_MESSAGE);
      }
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
    return proxy;
  }

  private static void requireSecretFits(ConnectionAuthMethod method, String secret) {
    if (blankToNull(secret) != null && !method.usesAppRegistration()) {
      throw new ValidationException(
          "Ein Client-Secret gehört nur zu einer Anmeldeart mit App-Registrierung");
    }
  }

  /** Field names and non-secret values only; the secret appears as whether one is set. */
  static Map<String, Object> auditState(ConnectionProfile profile) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("name", profile.getName());
    if (profile.isMcpServer()) {
      state.put("kind", profile.getKind().name());
      state.put("responsibleGroupId", Objects.toString(profile.getResponsibleGroupId(), ""));
      state.put("issuer", Objects.toString(profile.getIssuer(), ""));
    } else {
      state.put("sourceType", profile.getSourceType().key());
    }
    state.put("serverUrl", profile.getServerUrl());
    state.put("authMethod", profile.getAuthMethod().name());
    state.put("ownership", profile.getOwnership().name());
    state.put("clientIdSet", profile.getClientId() != null);
    state.put("clientSecretSet", profile.isClientSecretSet());
    state.put("tenantSet", profile.getTenant() != null);
    state.put("scopesSet", profile.getScopes() != null);
    state.put("connectorSettingsSet", profile.getConnectorSettings() != null);
    state.put("sourceProxy", profile.getSourceProxy() == null ? "" : profile.getSourceProxy());
    state.put("sourceInsecureSsl", profile.isSourceInsecureSsl());
    ProfileEndpoints endpoints = profile.getEndpoints();
    if (!endpoints.equals(ProfileEndpoints.NONE)) {
      state.put("authorizationEndpoint", Objects.toString(endpoints.authorization(), ""));
      state.put("tokenEndpoint", Objects.toString(endpoints.token(), ""));
      state.put("revocationEndpoint", Objects.toString(endpoints.revocation(), ""));
    }
    return state;
  }

  /**
   * Records that {@code caller} tested the profile's own sign-in, with whether it held; the
   * provider's message stays out of the audit.
   */
  @Transactional
  public void signInTested(CurrentUser caller, UUID profileId, boolean success) {
    record(
        caller,
        AuditEventType.CONNECTION_PROFILE_SIGN_IN_TESTED,
        get(profileId),
        null,
        Map.of("success", success),
        success ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE);
  }

  @Transactional
  void record(
      CurrentUser caller,
      AuditEventType type,
      ConnectionProfile profile,
      Map<String, Object> before,
      Map<String, Object> after) {
    record(caller, type, profile, before, after, AuditOutcome.SUCCESS);
  }

  private void record(
      CurrentUser caller,
      AuditEventType type,
      ConnectionProfile profile,
      Map<String, Object> before,
      Map<String, Object> after,
      AuditOutcome outcome) {
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(type)
            .object(AuditObjectType.SYSTEM_SETTING, profile.getId(), "Zugang " + profile.getName())
            .before(before)
            .after(after)
            .outcome(outcome)
            .build());
  }

  private static String required(String value, String field, int maxLength) {
    String trimmed = blankToNull(value);
    if (trimmed == null) {
      throw new ValidationException(field + " ist erforderlich");
    }
    return limited(trimmed, field, maxLength);
  }

  private static String optional(String value, String field, int maxLength) {
    String trimmed = blankToNull(value);
    return trimmed == null ? null : limited(trimmed, field, maxLength);
  }

  private static String limited(String value, String field, int maxLength) {
    if (value.length() > maxLength) {
      throw new ValidationException(field + " darf höchstens " + maxLength + " Zeichen umfassen");
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static NotFoundException notFound() {
    return new NotFoundException("Zugang nicht gefunden");
  }

  /** The values and the secret of a request, a service account key read. */
  private record Keyed(ConnectionProfileValues values, String secret) {}

  /**
   * Connections of libraries a change would cut off, the libraries behind them, the persons'
   * connected accounts (masked), the connectors' refusals of shared libraries in a proposed change
   * - empty without one - the refused private libraries (masked, {@code null} on a profile without
   * persons) and what saving it discards ({@link Discards#NONE} without one).
   */
  public record ProfileImpact(
      long connections,
      long libraries,
      PersonCount connectedAccounts,
      List<ChangeRejection> rejections,
      PersonCount rejectedPrivateLibraries,
      Discards discards) {

    /** The shared libraries whose run state a changed default only the profile sets discards. */
    public long fullSyncLibraries() {
      return discards.fullSyncs();
    }
  }

  /**
   * What saving a change discards, from the plan the save carries out; private libraries count
   * nowhere, and persons only through {@link PersonNumbers}.
   *
   * @param connections the shared connections that lose every secret and token held under the
   *     profile - a new address or registration - and must be signed in anew; 0 for any other
   *     change
   * @param secrets the shared libraries whose stored secret goes, to be entered anew
   * @param configurations the shared libraries whose effective configuration changes
   * @param connectedAccounts all connected accounts of the profile, masked, where the change ends
   *     them; {@code null} where it ends none
   * @param fullSyncLabels the changed defaults only the profile sets
   * @param fullSyncs the shared libraries whose run state they discard
   */
  public record Discards(
      long connections,
      long secrets,
      long configurations,
      PersonCount connectedAccounts,
      List<String> fullSyncLabels,
      long fullSyncs) {

    public static final Discards NONE = new Discards(0, 0, 0, null, List.of(), 0);

    /**
     * The question saving asks first - the message of the 409 {@value #CONFIRMATION_REQUIRED} -
     * {@code null} where it asks none. It names persons without a number.
     */
    public String confirmation() {
      boolean persons = connectedAccounts != null;
      if (connections == 0 && secrets == 0 && !persons && fullSyncs == 0) {
        return null;
      }
      StringBuilder text = new StringBuilder();
      if (connections > 0) {
        text.append("Die Änderung verwirft alle Zugangsdaten und Token dieses Zugangs; ")
            .append(libraries(connections, "Verbindung muss", "Verbindungen müssen"))
            .append(" neu angemeldet werden");
        if (secrets > 0) {
          text.append(", die gespeicherten Zugangsdaten von ")
              .append(libraries(secrets, "Bibliothek", "Bibliotheken"))
              .append(" sind neu einzutragen");
        }
        text.append(persons ? ", etwaige verbundene Konten von Personen enden. " : ". ");
      } else if (secrets > 0 || persons) {
        text.append("Die Änderung verwirft die Zugangsdaten ")
            .append(
                secrets == 0
                    ? ""
                    : "von "
                        + libraries(secrets, "Bibliothek", "Bibliotheken")
                        + (persons ? " sowie " : ""))
            .append(persons ? "etwaiger verbundener Konten von Personen" : "")
            .append(" dieses Zugangs. ");
      }
      if (fullSyncs > 0) {
        text.append("Die Vorgabe „")
            .append(String.join("“, „", fullSyncLabels))
            .append("“ ändert sich: Der Abgleichsstand von ")
            .append(libraries(fullSyncs, "Bibliothek", "Bibliotheken"))
            .append(" wird verworfen, der nächste Lauf liest die Quelle vollständig neu. ");
      }
      return text.append("Bitte bestätigen.").toString();
    }

    private static String libraries(long count, String one, String many) {
      return count + " " + (count == 1 ? one : many);
    }
  }

  /**
   * @param dropsPersons whether the new ownership no longer admits persons
   * @param rebindsPersons whether persons' secrets stand for another target afterwards
   * @param forPersons whether the profile admitted persons before the change
   * @param connections the connections of shared libraries
   * @param privateLibraries the private libraries on the profile
   */
  private record ProfileChange(
      ConnectionProfileValues values,
      boolean addressChanged,
      boolean registrationChanged,
      boolean dropsPersons,
      boolean rebindsPersons,
      boolean forPersons,
      long connections,
      List<Move> moves,
      Set<UUID> privateLibraries,
      List<String> fullSyncLabels) {

    /**
     * The private libraries saving releases from the profile: every one where the ownership no
     * longer admits persons, else those the connector refuses.
     */
    Set<UUID> released(PrivateLibraryRelease.Verdict verdict) {
      return dropsPersons ? privateLibraries : verdict.released();
    }

    /** Whether a changed default only the profile sets discards the run state on the profile. */
    boolean resetsRunState() {
      return !fullSyncLabels.isEmpty();
    }

    /**
     * The shared libraries whose run state the change discards - the number the administration is
     * told; the private ones are reset alike, uncounted.
     */
    long fullSyncs() {
      return resetsRunState() ? sharedLibraries().size() : 0;
    }

    List<KnowledgeLibrary> sharedLibraries() {
      return sharedMoves().map(Move::library).toList();
    }

    /** The shared libraries whose stored secret the change discards. */
    long secretsDiscarded() {
      return sharedMoves().filter(Move::discardsSecret).count();
    }

    /** The shared libraries whose effective configuration the change alters. */
    long configurationsChanged() {
      return sharedMoves().filter(Move::changesConfiguration).count();
    }

    private Stream<Move> sharedMoves() {
      return moves.stream().filter(move -> !privateLibraries.contains(move.library().getId()));
    }

    /** Whether persons' connections end - told without saying whether there are any. */
    boolean personsConcerned() {
      return forPersons && (discardsAll() || dropsPersons || rebindsPersons);
    }

    boolean discardsAll() {
      return addressChanged || registrationChanged;
    }

    /** The libraries whose stored secret the change discards. */
    Set<UUID> discarding() {
      return moves.stream()
          .filter(move -> discardsAll() || move.discardsSecret())
          .map(move -> move.library().getId())
          .collect(Collectors.toSet());
    }

    /** The connections the audit names as discarded. */
    long affected() {
      return discardsAll() ? connections : moves.stream().filter(Move::discardsSecret).count();
    }
  }
}
