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
import io.opaa.connection.token.ConnectionSecrets.Discarded;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ProfileDeclaration;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, changes and deletes connection profiles - system administration only, enforced by the
 * caller. A changed server address or app registration discards every secret held under the profile
 * - of libraries and of persons, whose connections end through {@link PersonConnections} - after a
 * confirmation once connections exist; a new client secret alone discards nothing. The client
 * secret is encrypted here and never returned; the audit names fields and library counts.
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
  private final PersonNumbers personNumbers;
  private final SourceTransitions transitions;
  private final CredentialsEncryptor encryptor;
  private final AuditEventRecorder audit;
  private final CapabilityService capabilities;
  private final Clock clock;

  public ConnectionProfileService(
      ConnectionProfileRepository profiles,
      LibraryConnectionRepository connections,
      KnowledgeLibraryRepository libraries,
      SourceConnectorRegistry connectors,
      ConnectionSecrets secrets,
      PersonConnections persons,
      PersonNumbers personNumbers,
      SourceTransitions transitions,
      CredentialsEncryptor encryptor,
      AuditEventRecorder audit,
      CapabilityService capabilities,
      Clock clock) {
    this.profiles = profiles;
    this.connections = connections;
    this.libraries = libraries;
    this.connectors = connectors;
    this.secrets = secrets;
    this.persons = persons;
    this.personNumbers = personNumbers;
    this.transitions = transitions;
    this.encryptor = encryptor;
    this.audit = audit;
    this.capabilities = capabilities;
    this.clock = clock;
  }

  public List<ConnectionProfile> list() {
    return profiles.findAllByOrderByNameAsc();
  }

  /** The profiles a library of {@code sourceType} may be connected through. */
  public List<ConnectionProfile> selectableFor(SourceType sourceType) {
    return profiles.findBySourceTypeOrderByNameAsc(sourceType).stream()
        .filter(profile -> profile.getOwnership().admitsLibraries())
        .toList();
  }

  public ConnectionProfile get(UUID id) {
    return profiles.findById(id).orElseThrow(ConnectionProfileService::notFound);
  }

  /** What a change of address or registration, a shutdown or a deletion would cut off. */
  public ProfileImpact impact(UUID id) {
    get(id);
    long libraryConnections = connections.countByProfileId(id);
    return new ProfileImpact(
        libraryConnections, libraryConnections, personNumbers.totalOf(id), List.of());
  }

  /** The connections of each of {@code profiles}, with one query for all of them. */
  public Map<UUID, Long> connectionCounts(List<ConnectionProfile> profiles) {
    Map<UUID, Long> counts = new LinkedHashMap<>();
    if (profiles.isEmpty()) {
      return counts;
    }
    for (LibraryConnectionRepository.ProfileConnectionCount row :
        connections.countByProfileIdIn(profiles.stream().map(ConnectionProfile::getId).toList())) {
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
    ConnectionProfileValues validated = validate(connector, values, null);
    requireSecretFits(validated.authMethod(), secret);
    Instant now = clock.instant();
    ConnectionProfile profile = new ConnectionProfile(sourceType, now);
    profile.replace(validated, encryptor.encrypt(blankToNull(secret)), now);
    profiles.save(profile);
    record(caller, AuditEventType.CONNECTION_PROFILE_CREATED, profile, null, auditState(profile));
    return profile;
  }

  /**
   * What a proposed change of profile {@code id} affects, with every connector refusal; nothing is
   * written, and the connectors are asked outside any transaction.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ProfileImpact preview(UUID id, ConnectionProfileValues values) {
    ProfileChange change = plan(get(id), values);
    return new ProfileImpact(
        change.connections(),
        change.connections(),
        personNumbers.totalOf(id),
        transitions.check(change.moves(), new Answers()));
  }

  /**
   * Asks the connector of every library a proposed change of profile {@code id} alters, outside any
   * transaction, so their costly checks run before the write; {@link #update} takes the answers and
   * asks only for what changed meanwhile. A change still needing {@code confirmed} is refused
   * first, before any connector is asked.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Answers check(UUID id, ConnectionProfileValues values, boolean confirmed) {
    ProfileChange change = plan(get(id), values);
    if (!confirmed) {
      requireNothingDiscarded(change);
    }
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
   * it, anything else replaces it. A new address, client id, tenant, scope list or sign-in method
   * discards every secret held under the profile, a new binding the secrets it concerns - refused
   * with 409 {@value #CONFIRMATION_REQUIRED} while there are such and {@code confirmed} is false.
   * Every library whose effective configuration changes passes its connector first ({@code answers}
   * given by {@link #check}); one refusal leaves profile and libraries unchanged (400 {@value
   * ChangeRejection#PROFILE_CHANGE_REJECTED}).
   */
  @Transactional
  public ConnectionProfile update(
      CurrentUser caller,
      UUID id,
      ConnectionProfileValues values,
      String secret,
      boolean confirmed,
      Answers answers) {
    ConnectionProfile profile = get(id);
    ProfileChange change = plan(profile, values);
    ConnectionProfileValues validated = change.values();
    String newSecret = blankToNull(secret);
    requireSecretFits(validated.authMethod(), newSecret);
    String ciphertext;
    if (!validated.authMethod().usesAppRegistration()) {
      ciphertext = null;
    } else if (secret == null) {
      ciphertext = profile.getClientSecretCiphertext();
    } else {
      ciphertext = encryptor.encrypt(newSecret);
    }
    if (!confirmed) {
      requireNothingDiscarded(change);
    }
    Set<UUID> discarding = change.discarding();
    long affected = change.affected();
    List<ChangeRejection> rejections = transitions.check(change.moves(), answers);
    if (!rejections.isEmpty()) {
      throw ChangeRejection.refusingProfileChange(rejections);
    }
    Map<String, Object> before = auditState(profile);
    profile.replace(validated, ciphertext, clock.instant());
    profiles.save(profile);
    for (Move move : change.moves()) {
      KnowledgeLibrary library = move.library();
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
      libraries.save(library);
    }
    if (change.discardsAll()) {
      ConnectionEndCause cause =
          change.addressChanged()
              ? ConnectionEndCause.ADDRESS_CHANGED
              : ConnectionEndCause.REGISTRATION_CHANGED;
      secrets.discardAllUnder(id, cause);
      persons.endAllUnder(id, cause, caller.id());
    } else if (change.dropsPersons()) {
      persons.endAllUnder(id, ConnectionEndCause.PROFILE_CHANGED, caller.id());
    }
    for (Move move : change.moves()) {
      transitions.record(caller, move.library(), transitions.applied(move));
    }
    Map<String, Object> after = auditState(profile);
    if (affected > 0) {
      after.put("connectionsDiscarded", affected);
    }
    record(caller, AuditEventType.CONNECTION_PROFILE_CHANGED, profile, before, after);
    return profile;
  }

  /** Refuses {@code change} with 409 while it discards stored secrets. */
  private static void requireNothingDiscarded(ProfileChange change) {
    long affected = change.affected();
    // asked on every profile for persons, whether or not one is connected: the text tells nothing
    boolean persons = change.personsConcerned();
    if (affected > 0 || persons) {
      throw new ConflictException(
          "Die Änderung verwirft die Zugangsdaten "
              + (affected == 0
                  ? ""
                  : "von "
                      + affected
                      + (affected == 1 ? " Verbindung" : " Verbindungen")
                      + (persons ? " sowie " : ""))
              + (persons ? "etwaiger verbundener Konten von Personen" : "")
              + " dieses Zugangs. Bitte bestätigen.",
          CONFIRMATION_REQUIRED);
    }
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
    boolean addressChanged = !profile.getServerUrl().equals(validated.serverUrl());
    boolean registrationChanged =
        profile.getAuthMethod() != validated.authMethod()
            || !Objects.equals(profile.getClientId(), validated.clientId())
            || !Objects.equals(profile.getTenant(), validated.tenant())
            || !Objects.equals(profile.getScopes(), validated.scopes());
    ConnectionProfile candidate = profile.candidate(validated);
    List<LibraryConnection> connected = connections.findByProfileId(profile.getId());
    List<KnowledgeLibrary> found =
        connected.isEmpty()
            ? List.of()
            : libraries.findAllById(
                connected.stream().map(LibraryConnection::getLibraryId).toList());
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
    return new ProfileChange(
        validated,
        addressChanged,
        registrationChanged,
        dropsPersons,
        profile.getOwnership().admitsPersons(),
        connected.size(),
        moves);
  }

  /**
   * The emergency shutdown "Alle Verbindungen trennen": discards every secret, keeps the profile.
   */
  @Transactional
  public ProfileImpact disconnectAll(CurrentUser caller, UUID id) {
    ConnectionProfile profile = get(id);
    PersonCount ended = personNumbers.totalOf(id);
    Discarded discarded = secrets.discardAllUnder(id, ConnectionEndCause.EMERGENCY);
    persons.endAllUnder(id, ConnectionEndCause.EMERGENCY, caller.id());
    record(
        caller,
        AuditEventType.CONNECTION_PROFILE_DISCONNECTED,
        profile,
        null,
        Map.of("connectionsDisconnected", discarded.libraries()));
    return new ProfileImpact(discarded.libraries(), discarded.libraries(), ended, List.of());
  }

  /**
   * Deletes the profile. Its connections lose their secrets and stay without a profile ("Zugang
   * entfernt"); their libraries keep their content and run no more. Its releases are withdrawn, so
   * a profile is never released before it exists.
   */
  @Transactional
  public void delete(CurrentUser caller, UUID id) {
    ConnectionProfile profile = get(id);
    List<LibraryConnection> affected = connections.findByProfileId(id);
    secrets.discardAllUnder(id, ConnectionEndCause.PROFILE_DELETED);
    persons.endAllUnder(id, ConnectionEndCause.PROFILE_DELETED, caller.id());
    Instant now = clock.instant();
    for (LibraryConnection connection : affected) {
      connection.moveTo(null, now);
    }
    connections.saveAll(affected);
    Map<String, Object> before = auditState(profile);
    before.put("connectionsRemoved", affected.size());
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
      if (clientId == null) {
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
    ConnectorData settings = declaration.defaults().read(values.connectorSettings());
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
        proxyOf(values.sourceProxy()),
        values.sourceInsecureSsl());
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
  private static Map<String, Object> auditState(ConnectionProfile profile) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("name", profile.getName());
    state.put("sourceType", profile.getSourceType().key());
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
    return state;
  }

  private void record(
      CurrentUser caller,
      AuditEventType type,
      ConnectionProfile profile,
      Map<String, Object> before,
      Map<String, Object> after) {
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(type)
            .object(AuditObjectType.SYSTEM_SETTING, profile.getId(), "Zugang " + profile.getName())
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
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

  /**
   * Connections of libraries a change would cut off, the libraries behind them, the persons'
   * connected accounts (masked), and the connectors' refusals of a proposed change - empty without
   * one.
   */
  public record ProfileImpact(
      long connections,
      long libraries,
      PersonCount connectedAccounts,
      List<ChangeRejection> rejections) {}

  /**
   * @param dropsPersons whether the new ownership no longer admits persons
   * @param forPersons whether the profile admitted persons before the change
   */
  private record ProfileChange(
      ConnectionProfileValues values,
      boolean addressChanged,
      boolean registrationChanged,
      boolean dropsPersons,
      boolean forPersons,
      long connections,
      List<Move> moves) {

    /** Whether persons' connections end - told without saying whether there are any. */
    boolean personsConcerned() {
      return forPersons && (discardsAll() || dropsPersons);
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

    /** The connections the confirmation counts. */
    long affected() {
      return discardsAll() ? connections : moves.stream().filter(Move::discardsSecret).count();
    }
  }
}
