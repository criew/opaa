package io.opaa.auth;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

  // Bounds for searchInOrganization below - see its Javadoc.
  private static final int SEARCH_RESULT_LIMIT = 20;
  private static final int SEARCH_MIN_QUERY_LENGTH = 2;

  // Throttles lastLoginAt writes to at most once per user per interval - 5 minutes of staleness is
  // an acceptable trade for dropping the per-request UPDATE.
  private static final Duration LAST_LOGIN_UPDATE_THRESHOLD = Duration.ofMinutes(5);

  private static final String BOOTSTRAP_ROLE_MESSAGE =
      "Die Rolle des Notanker-Kontos der Systemverwaltung kann nicht geändert werden.";

  private final UserRepository userRepository;
  private final InitialAdminPolicy initialAdminPolicy;
  private final OidcProviderRegistry providerRegistry;
  private final OidcProviderRepository providerRepository;
  private final TokenRoleSynchronizer roleSynchronizer;
  private final LocalAdminAvailabilityGuard adminGuard;
  private final LocalCredentialsRepository localCredentials;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public UserService(
      UserRepository userRepository,
      InitialAdminPolicy initialAdminPolicy,
      OidcProviderRegistry providerRegistry,
      OidcProviderRepository providerRepository,
      TokenRoleSynchronizer roleSynchronizer,
      LocalAdminAvailabilityGuard adminGuard,
      LocalCredentialsRepository localCredentials,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.userRepository = userRepository;
    this.initialAdminPolicy = initialAdminPolicy;
    this.providerRegistry = providerRegistry;
    this.providerRepository = providerRepository;
    this.roleSynchronizer = roleSynchronizer;
    this.adminGuard = adminGuard;
    this.localCredentials = localCredentials;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /**
   * Per-request provisioning (ADR-0025, Entscheidung 4) through the claim mapping of the provider
   * owning the issuer, Keycloak defaults otherwise. A provider with a roles or groups claim is
   * authoritative; groups and personal space follow the {@link UserProvisionedEvent} published
   * once. A token without a usable claim never reads as "no role" or "no groups".
   */
  public User provisionFromToken(Jwt jwt) {
    String issuer = JwtUserClaims.issuer(jwt);
    if (LocalIssuer.URN.equals(issuer)) {
      return findLocalAccount(jwt);
    }
    Optional<OidcProvider> provider = providerRegistry.findEnabledByIssuer(issuer);
    OidcClaimMapping mapping =
        provider.map(OidcProvider::getClaimMapping).orElseGet(OidcClaimMapping::keycloakDefaults);
    TokenClaims claims = TokenClaims.read(jwt, mapping);
    UserCreationResult result =
        findOrCreate(claims.subject(), issuer, claims.email(), claims.displayName());
    User user = result.user();
    if (provider.isPresent() && mapping.rolesClaim() != null) {
      user = roleSynchronizer.apply(user, provider.get(), claims.roles());
    }
    eventPublisher.publishEvent(
        provider.isPresent() && mapping.groupsClaim() != null
            ? UserProvisionedEvent.withTokenGroups(
                user, result.createdHere(), provider.get(), claims.groups())
            : UserProvisionedEvent.withoutTokenGroups(user, result.createdHere()));
    return user;
  }

  /**
   * The local issuer only finds, never provisions (ADR-0033, Entscheidung 8): the account must
   * exist, nothing is written back from the token and {@link InitialAdminPolicy} is not consulted.
   */
  private User findLocalAccount(Jwt jwt) {
    User user =
        userRepository
            .findBySubjectAndIssuer(jwt.getSubject(), LocalIssuer.URN)
            .orElseThrow(
                () -> new UserNotFoundException("Kein lokales Konto zu diesem Token vorhanden"));
    user = touchLastLogin(user);
    eventPublisher.publishEvent(UserProvisionedEvent.withoutTokenGroups(user, false));
    return user;
  }

  /**
   * Not {@code @Transactional}: each repository call commits on its own, so no caller holds two
   * pooled connections, and the listeners of {@link UserProvisionedEvent} always see a committed
   * row.
   */
  public User findOrCreateUser(String subject, String issuer, String email, String displayName) {
    UserCreationResult result = findOrCreate(subject, issuer, email, displayName);
    eventPublisher.publishEvent(
        UserProvisionedEvent.withoutTokenGroups(result.user(), result.createdHere()));
    return result.user();
  }

  private UserCreationResult findOrCreate(
      String subject, String issuer, String email, String displayName) {
    Optional<User> existing = userRepository.findBySubjectAndIssuer(subject, issuer);
    if (existing.isPresent()) {
      return new UserCreationResult(updateExistingUser(existing.get(), email, displayName), false);
    }
    return createOrFetchUser(subject, issuer, email, displayName);
  }

  /**
   * @param user the user row, either freshly inserted by this call or read back after losing a
   *     concurrent insert race - see {@link #createOrFetchUser}.
   * @param createdHere {@code true} only if <em>this</em> call's own insert attempt won the race
   *     and actually created {@code user}'s row - {@code false} both for an existing user found by
   *     {@code findOrCreate}'s initial lookup and for a race loser that read a concurrent winner's
   *     already-committed row.
   */
  private record UserCreationResult(User user, boolean createdHere) {}

  /**
   * {@code lastLoginAt} is refreshed only after {@link #LAST_LOGIN_UPDATE_THRESHOLD}; {@code
   * email}/{@code displayName} (identity-provider claims) are written immediately whenever they
   * differ from the stored value. No {@link UserRepository#save} call when none of the three
   * changed.
   */
  private User updateExistingUser(User existing, String email, String displayName) {
    Instant now = clock.instant();
    boolean changed = false;
    if (lastLoginDue(existing, now)) {
      existing.setLastLoginAt(now);
      changed = true;
    }
    if (email != null && !email.equals(existing.getEmail())) {
      existing.setEmail(email);
      changed = true;
    }
    if (displayName != null && !displayName.equals(existing.getDisplayName())) {
      existing.setDisplayName(displayName);
      changed = true;
    }
    return changed ? userRepository.save(existing) : existing;
  }

  private User touchLastLogin(User existing) {
    Instant now = clock.instant();
    if (!lastLoginDue(existing, now)) {
      return existing;
    }
    existing.setLastLoginAt(now);
    return userRepository.save(existing);
  }

  private static boolean lastLoginDue(User existing, Instant now) {
    Instant lastLoginAt = existing.getLastLoginAt();
    return lastLoginAt == null
        || Duration.between(lastLoginAt, now).compareTo(LAST_LOGIN_UPDATE_THRESHOLD) >= 0;
  }

  /**
   * Creates a new user; the loser of two concurrent first logins reads the winner's committed row,
   * since the failed insert rolled back only its own implicit transaction. {@link
   * UserCreationResult#createdHere()} tells winner from loser.
   */
  private UserCreationResult createOrFetchUser(
      String subject, String issuer, String email, String displayName) {
    try {
      return new UserCreationResult(insertUser(subject, issuer, email, displayName), true);
    } catch (DataIntegrityViolationException raceLost) {
      User winner =
          userRepository.findBySubjectAndIssuer(subject, issuer).orElseThrow(() -> raceLost);
      return new UserCreationResult(winner, false);
    }
  }

  private User insertUser(String subject, String issuer, String email, String displayName) {
    User newUser = new User(subject, issuer, email, displayName);
    newUser.setOrganizationId(Organization.DEFAULT_ID);
    // ADR-0033, Entscheidung 5: the address alone is not enough - only the dev issuer mints the
    // initial administrator any more, see InitialAdminPolicy.
    if (initialAdminPolicy.grantsSystemAdmin(email, issuer)) {
      newUser.setSystemRole(SystemRole.SYSTEM_ADMIN);
    }
    // saveAndFlush forces the INSERT to execute (and thus to fail, if it must) here, instead of
    // being deferred to a later flush point where the DataIntegrityViolationException could
    // surface somewhere other than this try block.
    return userRepository.saveAndFlush(newUser);
  }

  public Optional<User> findBySubjectAndIssuer(String subject, String issuer) {
    return userRepository.findBySubjectAndIssuer(subject, issuer);
  }

  /**
   * Scopes the admin user list to the caller's own organization: the organization boundary stops a
   * SYSTEM_ADMIN's reach just as it does everywhere else. The acting person is resolved by {@code
   * AdminController#listUsers}.
   */
  public List<User> findAllInOrganization(UUID organizationId) {
    return userRepository.findByOrganizationId(organizationId);
  }

  /**
   * The type-ahead search: a query shorter than {@link #SEARCH_MIN_QUERY_LENGTH} yields an empty
   * list, never the whole organization; matches are capped at {@link #SEARCH_RESULT_LIMIT}.
   */
  public List<User> searchInOrganization(UUID organizationId, String query) {
    String trimmed = query == null ? "" : query.trim();
    if (trimmed.length() < SEARCH_MIN_QUERY_LENGTH) {
      return List.of();
    }
    Pageable limit = PageRequest.of(0, SEARCH_RESULT_LIMIT);
    return userRepository.searchByOrganizationId(organizationId, trimmed, limit);
  }

  /**
   * The display name of the sign-in each of {@code found} comes from, by account id - absent where
   * no configured provider carries the account's issuer any more. One read of the provider rows for
   * all of them.
   */
  public Map<UUID, String> providerNamesOf(Collection<User> found) {
    if (found.isEmpty()) {
      return Map.of();
    }
    Map<String, String> nameByIssuer = new HashMap<>();
    providerRepository
        .findAll()
        .forEach(
            provider ->
                nameByIssuer.put(
                    OidcIssuerUris.normalize(provider.getIssuerUri()), provider.getDisplayName()));
    Map<UUID, String> names = new HashMap<>();
    for (User user : found) {
      String name = nameByIssuer.get(OidcIssuerUris.normalize(user.getIssuer()));
      if (name != null) {
        names.put(user.getId(), name);
      }
    }
    return names;
  }

  /**
   * Changes the system role and audits it in the same transaction. Object and subject of the entry
   * are the same pseudonym and the label is {@code null}, so no plain id or e-mail sits next to the
   * pseudonym. A user of another organization is a 404.
   */
  @Transactional
  public User updateRole(UUID userId, SystemRole role, CurrentUser actor) {
    User user =
        userRepository
            .findByIdAndOrganizationId(userId, actor.organizationId())
            .orElseThrow(() -> new UserNotFoundException("Benutzer nicht gefunden: " + userId));
    // ADR-0033, Entscheidungen 3 and 5: the bootstrap account never leaves SYSTEM_ADMIN; a return
    // to it restores the promised state of an account demoted before this rule was enforced
    if (role != SystemRole.SYSTEM_ADMIN
        && role != user.getSystemRole()
        && localCredentials.findById(userId).filter(LocalCredentials::isBootstrap).isPresent()) {
      throw new ConflictException(BOOTSTRAP_ROLE_MESSAGE, LocalCredentials.BOOTSTRAP_ACCOUNT);
    }
    // ADR-0025, Entscheidung 4: an enabled provider with a roles claim is authoritative - a role
    // written here would be overwritten by the account's next request. A disabled provider issues
    // no more tokens, so its accounts' roles are managed here again.
    providerRepository
        .findByNormalizedIssuerUri(OidcIssuerUris.normalize(user.getIssuer()))
        .filter(OidcProvider::isEnabled)
        .filter(provider -> provider.getClaimMapping().rolesClaim() != null)
        .ifPresent(
            provider -> {
              throw new ConflictException(
                  "Die Rolle wird vom Identitätsanbieter „"
                      + provider.getDisplayName()
                      + "“ verwaltet und kann hier nicht geändert werden.");
            });
    SystemRole previousRole = user.getSystemRole();
    // ADR-0033, Entscheidung 4: the manual withdrawal must leave a login-capable administrator
    if (previousRole == SystemRole.SYSTEM_ADMIN && role != SystemRole.SYSTEM_ADMIN) {
      adminGuard.requireAnotherLoginCapableAdmin(actor.organizationId(), user.getId());
    }
    user.setSystemRole(role);
    User saved = userRepository.save(user);
    if (previousRole != role) {
      // One event per elevated role left and one per elevated role entered, so SYSTEM_ADMIN ->
      // AUDITOR writes a revocation and a grant.
      UUID pseudonym = auditEventRecorder.pseudonymFor(saved.getId(), saved.getOrganizationId());
      if (previousRole == SystemRole.SYSTEM_ADMIN) {
        recordRoleChange(
            saved,
            actor.id(),
            pseudonym,
            AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED,
            previousRole,
            role);
      } else if (previousRole == SystemRole.AUDITOR) {
        recordRoleChange(
            saved, actor.id(), pseudonym, AuditEventType.AUDITOR_ROLE_REVOKED, previousRole, role);
      }
      if (role == SystemRole.SYSTEM_ADMIN) {
        recordRoleChange(
            saved,
            actor.id(),
            pseudonym,
            AuditEventType.SYSTEM_ADMIN_ROLE_GRANTED,
            previousRole,
            role);
      } else if (role == SystemRole.AUDITOR) {
        recordRoleChange(
            saved, actor.id(), pseudonym, AuditEventType.AUDITOR_ROLE_GRANTED, previousRole, role);
      }
    }
    return saved;
  }

  private void recordRoleChange(
      User saved,
      UUID actorUserId,
      UUID pseudonym,
      AuditEventType eventType,
      SystemRole previousRole,
      SystemRole role) {
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(saved.getOrganizationId())
            .actor(actorUserId)
            .type(eventType)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, saved.getId())
            .before(Map.of("role", previousRole.name()))
            .after(Map.of("role", role.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
