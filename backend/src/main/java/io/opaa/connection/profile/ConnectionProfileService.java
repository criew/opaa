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
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.ConnectionSecrets.Discarded;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceChangeGate;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
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
  private final SourceChangeGate changeGate;
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
    this.changeGate = new SourceChangeGate(connectors);
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
    return new ProfileImpact(libraryConnections, libraryConnections, personNumbers.totalOf(id));
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
   * Replaces every editable field; {@code secret} {@code null} keeps the stored one, blank clears
   * it, anything else replaces it. A new address, client id, tenant, scope list or sign-in method
   * discards every secret held under the profile; an ownership that no longer admits persons ends
   * their connections - refused with 409 {@value #CONFIRMATION_REQUIRED} while connections exist
   * and {@code confirmed} is false.
   */
  @Transactional
  public ConnectionProfile update(
      CurrentUser caller,
      UUID id,
      ConnectionProfileValues values,
      String secret,
      boolean confirmed) {
    ConnectionProfile profile = get(id);
    SourceConnector connector = connectorAdmittingProfiles(profile.getSourceType());
    ConnectionProfileValues validated = validate(connector, values, id);
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
    boolean addressChanged = !profile.getServerUrl().equals(validated.serverUrl());
    boolean registrationChanged =
        profile.getAuthMethod() != validated.authMethod()
            || !Objects.equals(profile.getClientId(), validated.clientId())
            || !Objects.equals(profile.getTenant(), validated.tenant())
            || !Objects.equals(profile.getScopes(), validated.scopes());
    boolean discards = addressChanged || registrationChanged;
    boolean dropsPersons =
        profile.getOwnership().admitsPersons() && !validated.ownership().admitsPersons();
    List<LibraryConnection> affected = discards ? connections.findByProfileId(id) : List.of();
    boolean personsConcerned = (discards || dropsPersons) && personNumbers.anyOn(id);
    if ((!affected.isEmpty() || personsConcerned) && !confirmed) {
      // persons are not counted here: the administration sees their number only masked
      throw new ConflictException(
          "Die Änderung verwirft die Zugangsdaten "
              + (affected.isEmpty()
                  ? ""
                  : "von "
                      + affected.size()
                      + (affected.size() == 1 ? " Bibliothek" : " Bibliotheken")
                      + (personsConcerned ? " und " : ""))
              + (personsConcerned ? "der verbundenen Konten" : "")
              + " dieses Zugangs. Bitte bestätigen.",
          CONFIRMATION_REQUIRED);
    }
    Map<String, Object> before = auditState(profile);
    String previousAddress = profile.getServerUrl();
    profile.replace(validated, ciphertext, clock.instant());
    profiles.save(profile);
    if (discards) {
      if (addressChanged) {
        moveAddresses(affected, previousAddress, validated.serverUrl());
      }
      ConnectionEndCause cause =
          addressChanged
              ? ConnectionEndCause.ADDRESS_CHANGED
              : ConnectionEndCause.REGISTRATION_CHANGED;
      secrets.discardAllUnder(id, cause);
      persons.endAllUnder(id, cause, caller.id());
    } else if (dropsPersons) {
      persons.endAllUnder(id, ConnectionEndCause.PROFILE_CHANGED, caller.id());
    }
    Map<String, Object> after = auditState(profile);
    if (!affected.isEmpty()) {
      after.put("connectionsDiscarded", affected.size());
    }
    record(caller, AuditEventType.CONNECTION_PROFILE_CHANGED, profile, before, after);
    return profile;
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
    return new ProfileImpact(discarded.libraries(), discarded.libraries(), ended);
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

  /**
   * Moves the address of every connected library under {@code oldAddress} to under {@code
   * newAddress}, without its secret, and discards the run state the move invalidates.
   */
  private void moveAddresses(
      List<LibraryConnection> affected, String oldAddress, String newAddress) {
    for (LibraryConnection connection : affected) {
      KnowledgeLibrary library = libraries.findById(connection.getLibraryId()).orElse(null);
      if (library != null && ServerAddress.covers(oldAddress, library.getSourceUrl())) {
        library.moveSourceUrl(ServerAddress.rebase(library.getSourceUrl(), oldAddress, newAddress));
        library.dropSourceCredentials();
        libraries.save(library);
        changeGate.addressMoved(library);
      }
    }
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
   * Connections of libraries a change would cut off, the libraries behind them, and the persons'
   * connected accounts, masked.
   */
  public record ProfileImpact(long connections, long libraries, PersonCount connectedAccounts) {}
}
