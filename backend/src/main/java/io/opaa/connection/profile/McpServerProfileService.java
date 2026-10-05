package io.opaa.connection.profile;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.group.GroupRepository;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The administration of MCP server profiles (ADR-0041, Entscheidung 5) - system administration
 * only, enforced by the caller. A profile without a connector: an address of a Streamable HTTP
 * endpoint, OAuth with the endpoints discovered on every save, persons as owners, a responsible
 * group. Discovery runs outside any transaction. A new address or registration - client id, scopes,
 * issuer or an endpoint - discards every token under the profile before the change and ends the
 * persons' connections, always confirmed first; a new client secret alone discards nothing. A
 * stored client secret never follows a new address, issuer or token endpoint. Deletion, shutdown
 * and lock are those of every profile.
 */
@Service
public class McpServerProfileService {

  /** Refusal of a change to another server keeping the client secret of the former one. */
  public static final String CLIENT_SECRET_REQUIRED = "MCP_SERVER_CLIENT_SECRET_REQUIRED";

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_FIELD_LENGTH = 255;
  private static final int MAX_SCOPES_LENGTH = 2000;

  private final ConnectionProfileRepository profiles;
  private final ConnectionProfileService profileService;
  private final ConnectorLockService locks;
  private final ConnectionSecrets secrets;
  private final PersonConnections persons;
  private final McpServerDiscovery discovery;
  private final GroupRepository groups;
  private final CredentialsEncryptor encryptor;
  private final TransactionTemplate transactions;
  private final Clock clock;

  McpServerProfileService(
      ConnectionProfileRepository profiles,
      ConnectionProfileService profileService,
      ConnectorLockService locks,
      ConnectionSecrets secrets,
      PersonConnections persons,
      McpServerDiscovery discovery,
      GroupRepository groups,
      CredentialsEncryptor encryptor,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.profiles = profiles;
    this.profileService = profileService;
    this.locks = locks;
    this.secrets = secrets;
    this.persons = persons;
    this.discovery = discovery;
    this.groups = groups;
    this.encryptor = encryptor;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * What the administration enters for an MCP server; the client secret travels beside it.
   *
   * @param scopes {@code null} for the scopes the server names as supported
   */
  public record Values(
      String name, String serverUrl, String clientId, String scopes, UUID responsibleGroupId) {}

  public List<ConnectionProfile> list() {
    return profiles.findByKindOrderByNameAsc(ProfileKind.MCP_SERVER);
  }

  /** The MCP server profile {@code id}; a connector profile is not found here. */
  public ConnectionProfile get(UUID id) {
    return profiles
        .findById(id)
        .filter(ConnectionProfile::isMcpServer)
        .orElseThrow(() -> new NotFoundException("MCP-Server nicht gefunden"));
  }

  /**
   * Creates an MCP server profile after discovering its authorization server.
   *
   * @throws ValidationException (German 400) for invalid values or a discovery refused
   */
  public ConnectionProfile create(CurrentUser caller, Values values, String secret) {
    Checked checked = check(caller, values, null);
    McpServerDiscovery.Metadata metadata = discovery.discover(checked.serverUrl());
    String ciphertext = blankToNull(secret) == null ? null : encryptor.encrypt(secret.strip());
    return transactions.execute(
        status -> {
          requireUniqueName(checked.name(), null);
          ConnectionProfile profile = ConnectionProfile.mcpServer(clock.instant());
          apply(profile, checked, metadata, ciphertext);
          profiles.save(profile);
          profileService.record(
              caller,
              AuditEventType.CONNECTION_PROFILE_CREATED,
              profile,
              null,
              ConnectionProfileService.auditState(profile));
          return profile;
        });
  }

  /**
   * Replaces every field of MCP server profile {@code id}, its authorization server discovered
   * anew; {@code secret} {@code null} keeps the stored one, blank clears it. A change discarding
   * tokens is refused with 409 {@value ConnectionProfileService#CONFIRMATION_REQUIRED} unless
   * {@code confirmed}; the question names persons without a number.
   */
  public ConnectionProfile update(
      CurrentUser caller, UUID id, Values values, String secret, boolean confirmed) {
    ConnectionProfile current = get(id);
    Checked checked = check(caller, values, current.getId());
    McpServerDiscovery.Metadata metadata = discovery.discover(checked.serverUrl());
    String newCiphertext =
        secret == null || blankToNull(secret) == null ? null : encryptor.encrypt(secret.strip());
    return transactions.execute(
        status -> {
          profiles.lockForChange(id);
          ConnectionProfile profile = get(id);
          requireUniqueName(checked.name(), id);
          boolean addressChanged = !profile.getServerUrl().equals(checked.serverUrl());
          boolean registrationChanged =
              !Objects.equals(profile.getClientId(), checked.clientId())
                  || !Objects.equals(profile.getScopes(), scopesOf(checked, metadata))
                  || !Objects.equals(profile.getIssuer(), metadata.issuer())
                  || !profile.getEndpoints().equals(metadata.endpoints());
          boolean discards = addressChanged || registrationChanged;
          boolean issuerChanged = !Objects.equals(profile.getIssuer(), metadata.issuer());
          boolean serverMoved =
              addressChanged
                  || issuerChanged
                  || !Objects.equals(profile.getEndpoints().token(), metadata.tokenEndpoint());
          if (serverMoved && secret == null && profile.isClientSecretSet()) {
            throw new ValidationException(
                "Der MCP-Server meldet sich künftig bei "
                    + metadata.issuer()
                    + " an, bisher bei "
                    + profile.getIssuer()
                    + ". Das hinterlegte Client-Secret gilt dort nicht: Bitte geben Sie das"
                    + " Client-Secret der App-Registrierung beim neuen Autorisierungsserver an,"
                    + " oder ein leeres für einen öffentlichen Client.",
                CLIENT_SECRET_REQUIRED);
          }
          if (discards && !confirmed) {
            throw new ConflictException(
                "Die Änderung verwirft alle Token dieses MCP-Servers; etwaige verbundene Konten"
                    + " von Personen enden."
                    + (issuerChanged
                        ? " Der Autorisierungsserver wechselt von "
                            + profile.getIssuer()
                            + " zu "
                            + metadata.issuer()
                            + "."
                        : "")
                    + " Bitte bestätigen.",
                ConnectionProfileService.CONFIRMATION_REQUIRED);
          }
          Map<String, Object> before = ConnectionProfileService.auditState(profile);
          ConnectionEndCause cause =
              addressChanged
                  ? ConnectionEndCause.ADDRESS_CHANGED
                  : ConnectionEndCause.REGISTRATION_CHANGED;
          if (discards) {
            // before the change: a token is revoked with the registration it was issued to
            secrets.discardAllUnder(id, cause);
          }
          String ciphertext = secret == null ? profile.getClientSecretCiphertext() : newCiphertext;
          apply(profile, checked, metadata, ciphertext);
          if (secret != null || discards) {
            profile.signInRejectedSince(null);
          }
          profiles.save(profile);
          if (discards) {
            persons.endAllUnder(id, cause, caller.id());
          }
          profileService.record(
              caller,
              AuditEventType.CONNECTION_PROFILE_CHANGED,
              profile,
              before,
              ConnectionProfileService.auditState(profile));
          return profile;
        });
  }

  /** Deletes MCP server profile {@code id}; every token on it is revoked, every connection ends. */
  public void delete(CurrentUser caller, UUID id) {
    transactions.executeWithoutResult(
        status -> {
          profiles.lockForDeletion(id);
          profileService.delete(caller, get(id));
        });
  }

  /**
   * The emergency shutdown of MCP server profile {@code id}: every token goes, the profile stays.
   *
   * @return the persons' connections it ended, masked
   */
  public PersonCount disconnectAll(CurrentUser caller, UUID id) {
    return transactions.execute(
        status -> {
          profiles.lockForChange(id);
          return profileService.disconnectAll(caller, get(id)).connectedAccounts();
        });
  }

  /**
   * Locks or unlocks MCP server profile {@code id}: a locked one connects and hands out nothing.
   */
  public ConnectionProfile lock(CurrentUser caller, UUID id, boolean locked) {
    return transactions.execute(status -> locks.lockProfile(caller, get(id), locked));
  }

  private void apply(
      ConnectionProfile profile,
      Checked checked,
      McpServerDiscovery.Metadata metadata,
      String ciphertext) {
    Instant now = clock.instant();
    profile.replace(
        new ConnectionProfileValues(
            checked.name(),
            checked.serverUrl(),
            ConnectionAuthMethod.OAUTH,
            ConnectionOwnership.PERSON,
            checked.clientId(),
            null,
            null,
            scopesOf(checked, metadata),
            null,
            null,
            false,
            metadata.endpoints()),
        ciphertext,
        now);
    profile.mcpServerFrame(checked.responsibleGroupId(), metadata.issuer());
  }

  private static String scopesOf(Checked checked, McpServerDiscovery.Metadata metadata) {
    String scopes = checked.scopes() != null ? checked.scopes() : metadata.scopes();
    if (scopes != null && scopes.length() > MAX_SCOPES_LENGTH) {
      throw new ValidationException("scopes ist zu lang");
    }
    return scopes;
  }

  /** {@code values} checked, the address in canonical form; the group must be the caller's. */
  private Checked check(CurrentUser caller, Values values, UUID existingId) {
    String name = required(values.name(), "name", MAX_NAME_LENGTH);
    requireUniqueName(name, existingId);
    String serverUrl = McpServerResource.canonical(values.serverUrl());
    String clientId = required(values.clientId(), "clientId", MAX_FIELD_LENGTH);
    String scopes = blankToNull(values.scopes());
    if (scopes != null && scopes.length() > MAX_SCOPES_LENGTH) {
      throw new ValidationException(
          "scopes darf höchstens " + MAX_SCOPES_LENGTH + " Zeichen umfassen");
    }
    UUID group = values.responsibleGroupId();
    if (group == null) {
      throw new ValidationException("responsibleGroupId ist erforderlich");
    }
    if (groups
        .findById(group)
        .filter(found -> found.getOrganizationId().equals(caller.organizationId()))
        .isEmpty()) {
      throw new ValidationException("Die verantwortliche Gruppe gibt es nicht");
    }
    return new Checked(name, serverUrl, clientId, scopes, group);
  }

  private void requireUniqueName(String name, UUID existingId) {
    if (profiles.existsByNameIgnoringCase(name, existingId)) {
      throw new ConflictException("Es gibt bereits einen Zugang mit dem Namen " + name);
    }
  }

  private static String required(String value, String field, int maxLength) {
    String trimmed = blankToNull(value);
    if (trimmed == null) {
      throw new ValidationException(field + " ist erforderlich");
    }
    if (trimmed.length() > maxLength) {
      throw new ValidationException(field + " darf höchstens " + maxLength + " Zeichen umfassen");
    }
    return trimmed;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private record Checked(
      String name, String serverUrl, String clientId, String scopes, UUID responsibleGroupId) {}
}
