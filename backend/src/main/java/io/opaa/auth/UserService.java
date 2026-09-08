package io.opaa.auth;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcIssuerUris;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

  // #778 review, finding 4: bounds for searchInOrganization below - see its Javadoc.
  private static final int SEARCH_RESULT_LIMIT = 20;
  private static final int SEARCH_MIN_QUERY_LENGTH = 2;

  // Throttles lastLoginAt writes to at most once per user per interval (#833) - 5 minutes of
  // staleness is an acceptable trade for dropping the per-request UPDATE.
  private static final Duration LAST_LOGIN_UPDATE_THRESHOLD = Duration.ofMinutes(5);

  private final UserRepository userRepository;
  private final InitialAdminPolicy initialAdminPolicy;
  private final OidcProviderRegistry providerRegistry;
  private final OidcProviderRepository providerRepository;
  private final TokenRoleSynchronizer roleSynchronizer;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public UserService(
      UserRepository userRepository,
      InitialAdminPolicy initialAdminPolicy,
      OidcProviderRegistry providerRegistry,
      OidcProviderRepository providerRepository,
      TokenRoleSynchronizer roleSynchronizer,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.userRepository = userRepository;
    this.initialAdminPolicy = initialAdminPolicy;
    this.providerRegistry = providerRegistry;
    this.providerRepository = providerRepository;
    this.roleSynchronizer = roleSynchronizer;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /**
   * The per-request provisioning behind {@link UserProvisioningFilter} (ADR-0025, Entscheidung 4):
   * the token is read through the claim mapping of the enabled provider that owns its issuer - or
   * the Keycloak-shaped defaults when no provider does, which is the {@code dev} mode - and the
   * account is found or created as before. For a provider with a roles or groups claim, the
   * provider is authoritative: the stored role is aligned with the token here, the token's group
   * memberships by the {@link UserProvisionedEvent} this method publishes exactly once - as does
   * the personal-space provisioning, which is why neither package is reached into from here.
   */
  public User provisionFromToken(Jwt jwt) {
    String issuer = JwtUserClaims.issuer(jwt);
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
   * Deliberately <b>not</b> {@code @Transactional} (#293/#299 code review): every {@link
   * UserRepository} call below already commits in its own implicit transaction, so no caller ever
   * holds two pooled connections at once - the earlier {@code @Transactional} version deadlocked
   * the whole pool under concurrent first logins. Everything that follows the user row (personal
   * space, token groups) therefore always sees a committed row, and runs as a listener of the
   * {@link UserProvisionedEvent} published here rather than inside a transaction opened here.
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
   * changed (#833).
   */
  private User updateExistingUser(User existing, String email, String displayName) {
    Instant now = clock.instant();
    boolean changed = false;
    Instant lastLoginAt = existing.getLastLoginAt();
    if (lastLoginAt == null
        || Duration.between(lastLoginAt, now).compareTo(LAST_LOGIN_UPDATE_THRESHOLD) >= 0) {
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

  /**
   * Creates a new user, tolerating the race of two concurrent first logins for the same {@code
   * subject}/{@code issuer} pair racing past the {@code findBySubjectAndIssuer} check above (#293).
   *
   * <p>Because {@link #findOrCreateUser} is deliberately not {@code @Transactional} (see its
   * Javadoc), {@link #insertUser} below runs in its own short-lived, implicit transaction on its
   * own connection - not one shared with this method's caller. A {@link
   * DataIntegrityViolationException} there rolls back only that one insert; nothing here is
   * poisoned by it, so the loser can simply read the row the winner has by now committed, instead
   * of surfacing a 500 for {@code uq_users_subject_issuer}. Same fallback-read pattern as {@code
   * SpaceService#ensureDefaultSpace}, but without that method's {@code REQUIRES_NEW} - there is no
   * ambient transaction here to escape from in the first place.
   *
   * <p>#307: also reports whether the insert attempt actually won, via {@link
   * UserCreationResult#createdHere()} - see {@link #findOrCreateUser}'s Javadoc for why the caller
   * needs to tell a genuine winner apart from a race loser here.
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
    // ADR-0025, Entscheidung 3: the address alone is not enough - only the trusted provider's
    // issuer may mint the initial administrator, see InitialAdminPolicy.
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
   * Scopes the admin user list to the caller's own organization (#271) - {@code findAll()} used to
   * return every organization's users, including to a SYSTEM_ADMIN, whose reach the organization
   * boundary must stop at just as it does everywhere else (#199), and whose acting person is
   * resolved by {@code AdminController#listUsers}.
   */
  public List<User> findAllInOrganization(UUID organizationId) {
    return userRepository.findByOrganizationId(organizationId);
  }

  /**
   * Backs {@code UserSearchController#listUsers} (#777, gated after #778 review finding 4). A
   * missing or too-short (below {@link #SEARCH_MIN_QUERY_LENGTH}) query returns an empty list
   * rather than falling back to the unbounded {@link #findAllInOrganization} - the caller is
   * expected to be a type-ahead picker that never even issues a request before the caller has typed
   * enough to narrow the result, not a page-load preload. Matches are capped at {@link
   * #SEARCH_RESULT_LIMIT} rows via {@link UserRepository#searchByOrganizationId}.
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
   * #392 code review, finding 3: the specification names "Erteilung und Entzug der
   * System-Admin-Rolle" explicitly in the first-stage event list, and this is the one method that
   * already performs it - {@code actor} is the person making the change (the {@code SYSTEM_ADMIN}
   * caller {@code AdminController#changeRole} enforces via {@code @PreAuthorize}), {@code
   * userId}/{@code role} describe the change itself. {@code @Transactional} (already present on
   * this method beforehand) is what makes the audit write commit or roll back together with the
   * role change itself, the same as every other write this method makes.
   *
   * <p><b>#392/#444 re-review: object and subject are the same person here</b> - {@code userId} is
   * both the account the event is about and the rights subject the role change affects. The first
   * version of this method used the real {@code userId} as {@code objectId} and {@code
   * saved.getEmail()} as {@code objectLabel} while {@code subjectRef} carried that same person's
   * pseudonym - the same row then held both the plain id/email and the pseudonym for the identical
   * person, trivially reversing this person's pseudonymisation everywhere else in the log, and (via
   * the email in {@code object_label}) surviving an account deletion that is supposed to make the
   * log unattributable again (docs/features/security-and-compliance.md, "Unveraenderlichkeit und
   * Loeschrecht"). Both {@code objectId} and {@code subjectId} now resolve to the same pseudonym
   * ({@link AuditEventRecorder#pseudonymFor}, called once and reused for both), and {@code
   * objectLabel} is {@code null} - there is no non-identifying label for "this one account" that
   * would not just be another name for the pseudonym already carried in {@code object_id}/{@code
   * subject_ref}.
   *
   * <p>{@code actor} carries the acting SYSTEM_ADMIN's {@code organizationId} to reject a target
   * user from another organization the same way {@code SpaceService#requireUserInOrganization}
   * does: a 404, not a 403, so a caller cannot distinguish "no such user" from "user in another
   * organization" even for the widest-reaching role in the system.
   */
  @Transactional
  public User updateRole(UUID userId, SystemRole role, CurrentUser actor) {
    User user =
        userRepository
            .findByIdAndOrganizationId(userId, actor.organizationId())
            .orElseThrow(() -> new UserNotFoundException("Benutzer nicht gefunden: " + userId));
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
    user.setSystemRole(role);
    User saved = userRepository.save(user);
    if (previousRole != role) {
      // #393 code review, finding 1: with three roles (USER/SYSTEM_ADMIN/AUDITOR), a single
      // "granted vs. revoked" branch on the *new* role alone is wrong - it mislabelled every
      // AUDITOR grant as SYSTEM_ADMIN_ROLE_REVOKED (USER -> AUDITOR: role != SYSTEM_ADMIN, so the
      // old two-valued branch always chose REVOKED, regardless of what actually happened). Instead,
      // write one event per elevated role actually left (previousRole) and one per elevated role
      // actually entered (role) - 0, 1 or 2 events depending on the transition:
      //   USER -> SYSTEM_ADMIN            : 1 event  (SYSTEM_ADMIN_ROLE_GRANTED)
      //   SYSTEM_ADMIN -> USER            : 1 event  (SYSTEM_ADMIN_ROLE_REVOKED)
      //   USER -> AUDITOR                 : 1 event  (AUDITOR_ROLE_GRANTED)
      //   AUDITOR -> USER                 : 1 event  (AUDITOR_ROLE_REVOKED)
      //   SYSTEM_ADMIN -> AUDITOR         : 2 events (SYSTEM_ADMIN_ROLE_REVOKED,
      // AUDITOR_ROLE_GRANTED)
      //   AUDITOR -> SYSTEM_ADMIN         : 2 events (AUDITOR_ROLE_REVOKED,
      // SYSTEM_ADMIN_ROLE_GRANTED)
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
