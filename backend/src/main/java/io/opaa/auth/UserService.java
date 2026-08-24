package io.opaa.auth;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.audit.AuditSubjectKind;
import io.opaa.observability.AuthMetrics;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@EnableConfigurationProperties(AuthProperties.class)
public class UserService {

  private static final Logger log = LoggerFactory.getLogger(UserService.class);

  // #778 review, finding 4: bounds for searchInOrganization below - see its Javadoc.
  private static final int SEARCH_RESULT_LIMIT = 20;
  private static final int SEARCH_MIN_QUERY_LENGTH = 2;

  // #833: every authenticated request ran findOrCreateUser -> updateExistingUser, which wrote
  // lastLoginAt unconditionally - an UPDATE on `users` per API call, regardless of how recently
  // the same user's last request already wrote it. This threshold caps that to at most one write
  // per user per interval; a stale lastLoginAt by up to this much is an acceptable trade for
  // dropping the per-request write. No property for this - the write-frequency/staleness
  // trade-off is an implementation detail with no operator-facing reason to vary per deployment.
  private static final Duration LAST_LOGIN_UPDATE_THRESHOLD = Duration.ofMinutes(5);

  private final UserRepository userRepository;
  private final SpaceService spaceService;
  private final AuthProperties authProperties;
  private final AuditEventRecorder auditEventRecorder;
  private final AuthMetrics authMetrics;
  private final Clock clock;

  public UserService(
      UserRepository userRepository,
      SpaceService spaceService,
      AuthProperties authProperties,
      AuditEventRecorder auditEventRecorder,
      AuthMetrics authMetrics,
      Clock clock) {
    this.userRepository = userRepository;
    this.spaceService = spaceService;
    this.authProperties = authProperties;
    this.auditEventRecorder = auditEventRecorder;
    this.authMetrics = authMetrics;
    this.clock = clock;
  }

  /**
   * Deliberately <b>not</b> {@code @Transactional} (#293 code review). Each {@link UserRepository}
   * call below already runs in its own implicit transaction (Spring Data's {@code
   * SimpleJpaRepository} methods are individually {@code @Transactional}), so no explicit
   * transaction demarcation is needed here, and none is wanted: a shared, still-open outer
   * transaction would hold one connection for the whole method while {@link #createOrFetchUser}
   * additionally needed a second, concurrently held connection to attempt its insert without
   * poisoning the outer one - see the retired {@code createOrFetchUser} Javadoc in the #293 PR
   * history. Under N concurrent first logins for the same subject, that held two connections per
   * caller for the outer transaction's whole lifetime; once N reached {@code
   * hikari.maximum-pool-size} (default 10), every outer transaction had claimed a connection and
   * held it while waiting on the unique index, no insert attempt could obtain the second connection
   * it needed, and the whole pool deadlocked until {@code connectionTimeout} - a worse failure than
   * the 500 this fix set out to remove, and reachable by ordinary login traffic (multiple requests
   * fire right after the SPA logs in). Without an ambient transaction here, {@link
   * #createOrFetchUser}'s insert attempt and its fallback read are each just one more short-lived,
   * independently connection-scoped call - never two connections held by the same caller at once.
   * {@link #ensurePersonalSpaceAfterCommit} below (#201's personal space provisioning; its sibling
   * personal library provisioning was removed again in #522) follows the exact same reasoning:
   * {@code ensureDefaultSpace} never runs inside an ambient transaction started here, for the same
   * connection-budget reason.
   *
   * <p><b>#307:</b> besides not holding two connections per caller, this method now also spends
   * fewer of them in total for the specific load #307 measured - many concurrent <em>first</em>
   * logins of <em>different</em> users, e.g. an organization's whole staff onboarding in one
   * morning. {@link #createOrFetchUser} reports back whether its own insert attempt actually won
   * (as opposed to losing a concurrent race and reading the winner's row - see that method's
   * Javadoc); only a genuine winner is guaranteed to be a user nobody has provisioned a personal
   * space for yet, so only that case skips {@code ensureDefaultSpace}'s otherwise-redundant {@code
   * existsBy} round trip via {@link SpaceService#ensureDefaultSpaceForNewUser} - see its Javadoc.
   */
  public User findOrCreateUser(String subject, String issuer, String email, String displayName) {
    Optional<User> existing = userRepository.findBySubjectAndIssuer(subject, issuer);
    boolean createdHere;
    User user;
    if (existing.isPresent()) {
      user = updateExistingUser(existing.get(), email, displayName);
      createdHere = false;
    } else {
      UserCreationResult result = createOrFetchUser(subject, issuer, email, displayName);
      user = result.user();
      createdHere = result.createdHere();
    }

    ensurePersonalSpaceAfterCommit(user.getId(), user.getOrganizationId(), createdHere);
    return user;
  }

  /**
   * @param user the user row, either freshly inserted by this call or read back after losing a
   *     concurrent insert race - see {@link #createOrFetchUser}.
   * @param createdHere {@code true} only if <em>this</em> call's own insert attempt won the race
   *     and actually created {@code user}'s row - {@code false} both for an existing user found by
   *     {@link #findOrCreateUser}'s initial lookup and for a race loser that read a concurrent
   *     winner's already-committed row.
   */
  private record UserCreationResult(User user, boolean createdHere) {}

  /**
   * #833: writes only what actually changed, instead of unconditionally overwriting {@code
   * lastLoginAt}/{@code email}/{@code displayName} and calling {@code save()} on every request.
   *
   * <p>{@code lastLoginAt} is refreshed only once {@link #LAST_LOGIN_UPDATE_THRESHOLD} has passed
   * since the stored value - the column exists for "is this account still active", a purpose a
   * multi-minute staleness window does not compromise, unlike email/displayName below, which are
   * source-of-truth claims from the identity provider and must never be allowed to lag. {@code
   * email}/{@code displayName} are written only when the incoming claim actually differs from the
   * stored value, not merely when it is present - the pre-fix code wrote the same value back on
   * every request whenever the claim was non-null.
   *
   * <p>If none of the three actually changed, this method returns {@code existing} unmodified
   * without calling {@link UserRepository#save}, so a burst of requests from the same, recently
   * seen user causes no write to {@code users} at all until the threshold next elapses or a claim
   * changes.
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
    if (isInitialAdmin(email)) {
      newUser.setSystemRole(SystemRole.SYSTEM_ADMIN);
    }
    // saveAndFlush forces the INSERT to execute (and thus to fail, if it must) here, instead of
    // being deferred to a later flush point where the DataIntegrityViolationException could
    // surface somewhere other than this try block.
    return userRepository.saveAndFlush(newUser);
  }

  /**
   * Runs {@link SpaceService#ensureDefaultSpace} only after the {@code users} row it needs has been
   * committed.
   *
   * <p>Historically (#265, fixed in the #280 follow-up), {@code findOrCreateUser} was
   * {@code @Transactional} and inserted the new user in a still-open transaction; {@code
   * ensureDefaultSpace} inserts the default space in its own {@code REQUIRES_NEW} transaction (see
   * its Javadoc), on a separate connection with its own snapshot that could not see the uncommitted
   * {@code users} row, so the insert violated {@code fk_spaces_owner} (now {@code
   * fk_spaces_owner_organization} as of migration 047) and the whole login failed. Deferring the
   * call to {@link TransactionSynchronization#afterCommit()} fixed that by guaranteeing the user
   * row was already committed and visible by the time the personal space was created. #201 later
   * added an equivalent personal-library provisioning call here; #522 removed it again (a user now
   * creates their own libraries, there is no automatic default), so this method is back to guarding
   * {@code ensureDefaultSpace} alone.
   *
   * <p>Since {@link #findOrCreateUser} was made deliberately non-{@code @Transactional} (#293 code
   * review - see its Javadoc), there is no ambient transaction synchronization active here to
   * register a callback with in the first place: every call below now always takes the immediate,
   * synchronous branch. That remains correct for the same reason the {@code afterCommit} deferral
   * did - each {@link UserRepository} call in {@code findOrCreateUser} already committed
   * independently by the time control reaches here, so the user row this method's caller passes in
   * is always already visible on any connection, including {@code ensureDefaultSpace}'s {@code
   * REQUIRES_NEW} one. The {@code isSynchronizationActive}/{@code registerSynchronization} branch
   * below is kept only as a defensive fallback for a caller running inside its own transaction
   * (there is none in production today) - it must not silently skip provisioning if one ever
   * exists.
   */
  private void ensurePersonalSpaceAfterCommit(
      UUID userId, UUID organizationId, boolean createdHere) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              ensurePersonalSpace(userId, organizationId, createdHere);
            }
          });
    } else {
      ensurePersonalSpace(userId, organizationId, createdHere);
    }
  }

  /**
   * Failures are logged, not rethrown (code review of #201/#305, still true after #522 removed the
   * sibling library provisioning): {@link #findOrCreateUser} has no ambient transaction to protect
   * (#293/#299), and this method is called unconditionally on <em>every</em> {@link
   * #findOrCreateUser} invocation, so a rethrown failure would fail the login request itself, and a
   * persistently failing provisioning would then fail every subsequent login for that user too -
   * turning a provisioning failure into a lockout, a worse outcome than the login proceeding
   * without (yet) having a personal space.
   *
   * <p>Called unconditionally for every {@link #findOrCreateUser} invocation, not only for newly
   * created users: {@code ensureDefaultSpace} is idempotent (it checks for an existing row first),
   * so a returning user whose personal space failed to provision on an earlier login gets one
   * created on their next login instead of being left without one indefinitely.
   *
   * <p>#307: {@code createdHere} selects {@link SpaceService#ensureDefaultSpaceForNewUser} for a
   * user this very call just created, skipping its otherwise-redundant existence check - see that
   * method's Javadoc. Every other case (an existing user, or a race loser that read a concurrent
   * winner's row) keeps calling the idempotent {@link SpaceService#ensureDefaultSpace}, which is
   * still the only one of the two that is actually safe to call when a personal space might already
   * exist.
   *
   * <p>#294 asked whether a failed provisioning attempt should become visible instead of merely
   * logged; {@link AuthMetrics#recordPersonalSpaceProvisioningFailed()} answers that with the
   * project's existing Micrometer counters ({@code IndexingMetrics}/{@code QueryMetrics} follow the
   * same pattern) rather than a log line alone - the running total is still included in the log
   * message below too, so a repeatedly failing provisioning stands out there as well instead of
   * blending into a stream of identical-looking single-occurrence errors.
   */
  private void ensurePersonalSpace(UUID userId, UUID organizationId, boolean createdHere) {
    try {
      if (createdHere) {
        spaceService.ensureDefaultSpaceForNewUser(userId, organizationId);
      } else {
        spaceService.ensureDefaultSpace(userId, organizationId);
      }
    } catch (RuntimeException e) {
      authMetrics.recordPersonalSpaceProvisioningFailed();
      log.error(
          "Failed to provision personal space for user {} (organization {}); will retry on next"
              + " login (failure #{} since startup)",
          userId,
          organizationId,
          (long) authMetrics.personalSpaceProvisioningFailedCount(),
          e);
    }
  }

  public Optional<User> findBySubjectAndIssuer(String subject, String issuer) {
    return userRepository.findBySubjectAndIssuer(subject, issuer);
  }

  public Optional<User> findById(UUID id) {
    return userRepository.findById(id);
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
   * <p><b>#271:</b> {@code actor} is now the full, already-resolved {@link User} rather than a bare
   * {@code UUID} - {@code AdminController#changeRole} already resolves it via
   * {@code @AuthenticationPrincipal Jwt} to enforce {@code @PreAuthorize}, and this method needs
   * the actor's {@code organizationId} to reject a target user from another organization the same
   * way {@code SpaceService#requireUserInOrganization} does: a 404, not a 403, so a caller cannot
   * distinguish "no such user" from "user in another organization" even for the widest-reaching
   * role in the system.
   */
  @Transactional
  public User updateRole(UUID userId, SystemRole role, User actor) {
    User user =
        userRepository
            .findByIdAndOrganizationId(userId, actor.getOrganizationId())
            .orElseThrow(() -> new UserNotFoundException("Benutzer nicht gefunden: " + userId));
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
            actor.getId(),
            pseudonym,
            AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED,
            previousRole,
            role);
      } else if (previousRole == SystemRole.AUDITOR) {
        recordRoleChange(
            saved,
            actor.getId(),
            pseudonym,
            AuditEventType.AUDITOR_ROLE_REVOKED,
            previousRole,
            role);
      }
      if (role == SystemRole.SYSTEM_ADMIN) {
        recordRoleChange(
            saved,
            actor.getId(),
            pseudonym,
            AuditEventType.SYSTEM_ADMIN_ROLE_GRANTED,
            previousRole,
            role);
      } else if (role == SystemRole.AUDITOR) {
        recordRoleChange(
            saved,
            actor.getId(),
            pseudonym,
            AuditEventType.AUDITOR_ROLE_GRANTED,
            previousRole,
            role);
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
        saved.getOrganizationId(),
        actorUserId,
        eventType,
        AuditObjectType.USER_ACCOUNT,
        pseudonym,
        null,
        AuditSubjectKind.USER,
        saved.getId(),
        Map.of("role", previousRole.name()),
        Map.of("role", role.name()),
        AuditOutcome.SUCCESS,
        null);
  }

  private boolean isInitialAdmin(String email) {
    String initialAdminEmail = authProperties.initialAdminEmail();
    return initialAdminEmail != null
        && !initialAdminEmail.isBlank()
        && initialAdminEmail.equalsIgnoreCase(email);
  }
}
