package io.opaa.auth;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.audit.AuditSubjectKind;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@EnableConfigurationProperties(AuthProperties.class)
public class UserService {

  private static final Logger log = LoggerFactory.getLogger(UserService.class);

  /**
   * Fixed-size striping for {@link #provisioningLockFor(UUID)}: bounds memory to exactly this many
   * {@link ReentrantLock} instances for the lifetime of the process, at the cost of two unrelated
   * users occasionally sharing a stripe and blocking on each other's provisioning - an acceptable
   * trade for a lock that exists purely to reduce database contention (see {@link
   * #ensurePersonalAssetsAfterCommit}'s Javadoc), never for correctness, which the database's
   * partial unique indexes still guarantee on their own.
   */
  private static final int PROVISIONING_LOCK_STRIPES = 64;

  private final ReentrantLock[] provisioningLocks = new ReentrantLock[PROVISIONING_LOCK_STRIPES];

  private final UserRepository userRepository;
  private final SpaceService spaceService;
  private final KnowledgeLibraryService libraryService;
  private final AuthProperties authProperties;
  private final AuditEventRecorder auditEventRecorder;

  public UserService(
      UserRepository userRepository,
      SpaceService spaceService,
      KnowledgeLibraryService libraryService,
      AuthProperties authProperties,
      AuditEventRecorder auditEventRecorder) {
    this.userRepository = userRepository;
    this.spaceService = spaceService;
    this.libraryService = libraryService;
    this.authProperties = authProperties;
    this.auditEventRecorder = auditEventRecorder;
    for (int i = 0; i < provisioningLocks.length; i++) {
      provisioningLocks[i] = new ReentrantLock();
    }
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
   * {@link #ensurePersonalAssetsAfterCommit} below (#201's personal space and personal library
   * provisioning) follows the exact same reasoning: neither {@code ensureDefaultSpace} nor {@code
   * ensurePersonalLibrary} ever runs inside an ambient transaction started here, for the same
   * connection-budget reason.
   */
  public User findOrCreateUser(String subject, String issuer, String email, String displayName) {
    User user =
        userRepository
            .findBySubjectAndIssuer(subject, issuer)
            .map(existing -> updateExistingUser(existing, email, displayName))
            .orElseGet(() -> createOrFetchUser(subject, issuer, email, displayName));

    ensurePersonalAssetsAfterCommit(user.getId(), user.getOrganizationId());
    return user;
  }

  private User updateExistingUser(User existing, String email, String displayName) {
    existing.setLastLoginAt(Instant.now());
    if (email != null) {
      existing.setEmail(email);
    }
    if (displayName != null) {
      existing.setDisplayName(displayName);
    }
    return userRepository.save(existing);
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
   */
  private User createOrFetchUser(String subject, String issuer, String email, String displayName) {
    try {
      return insertUser(subject, issuer, email, displayName);
    } catch (DataIntegrityViolationException raceLost) {
      return userRepository.findBySubjectAndIssuer(subject, issuer).orElseThrow(() -> raceLost);
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
   * Runs {@link SpaceService#ensureDefaultSpace} and {@link
   * KnowledgeLibraryService#ensurePersonalLibrary} only after the {@code users} row they need has
   * been committed - and always together, so provisioning never silently produces a personal space
   * without its personal library or the other way round (#201's "creating a user creates a personal
   * space and a personal library atomically").
   *
   * <p>Historically (#265, fixed in the #280 follow-up), {@code findOrCreateUser} was
   * {@code @Transactional} and inserted the new user in a still-open transaction; {@code
   * ensureDefaultSpace} inserts the default space in its own {@code REQUIRES_NEW} transaction (see
   * its Javadoc), on a separate connection with its own snapshot that could not see the uncommitted
   * {@code users} row, so the insert violated {@code fk_spaces_owner} and the whole login failed.
   * Deferring the call to {@link TransactionSynchronization#afterCommit()} fixed that by
   * guaranteeing the user row was already committed and visible by the time the personal space was
   * created. {@code ensurePersonalLibrary} (#201) inserts in its own {@code REQUIRES_NEW}
   * transaction the same way and is subject to exactly the same visibility requirement, so it is
   * called from this same method rather than from a second, parallel deferral mechanism.
   *
   * <p>Since {@link #findOrCreateUser} was made deliberately non-{@code @Transactional} (#293 code
   * review - see its Javadoc), there is no ambient transaction synchronization active here to
   * register a callback with in the first place: every call below now always takes the immediate,
   * synchronous branch. That remains correct for the same reason the {@code afterCommit} deferral
   * did - each {@link UserRepository} call in {@code findOrCreateUser} already committed
   * independently by the time control reaches here, so the user row this method's caller passes in
   * is always already visible on any connection, including {@code ensureDefaultSpace}'s and {@code
   * ensurePersonalLibrary}'s {@code REQUIRES_NEW} ones. The {@code isSynchronizationActive}/{@code
   * registerSynchronization} branch below is kept only as a defensive fallback for a caller running
   * inside its own transaction (there is none in production today) - it must not silently skip
   * provisioning if one ever exists.
   *
   * <p><b>{@code provisioningLockFor(userId)}, an in-process lock around the call below</b>
   * (#201/#305 code review, measured): {@code SpaceRepository#insertPersonalSpaceIfAbsent} and
   * {@code KnowledgeLibraryRepository#insertPersonalLibraryIfAbsent} each reduced their own round
   * trips to one via {@code ON CONFLICT ... DO NOTHING}, but under {@code
   * UserServiceCreationRaceIntegrationTest}'s 12-concurrent-first-login load that alone was not
   * enough (3 of 6 runs still failed without this lock). The bottleneck is <b>not</b> database-side
   * index contention - a follow-up measurement with 12 concurrent first logins of 12
   * <em>different</em> users (no index conflict possible at all) still hit the same 30-second
   * timeout, and {@code pg_stat_activity} showed no active session on the database during the
   * stall. The bottleneck is acquiring a Hikari connection at all: two provisioning calls per login
   * (space, then library), each needing its own short-lived connection, doubled the number of
   * concurrent connection requests competing for the same pool once #201 added the second call. An
   * in-process lock keyed by {@code userId} reduces that count for the case this test exercises and
   * that is also the realistic production trigger (many requests for the *same* user's very first
   * login, e.g. several tabs opened at once): only the first thread to acquire the lock reaches the
   * database at all; by the time each following thread acquires it, the winner has already
   * committed, so {@code ensureDefaultSpace}/{@code ensurePersonalLibrary}'s own {@code existsBy}
   * check returns {@code true} immediately and neither issues an insert, so neither needs a
   * connection either. Confirmed by {@code UserServiceCreationRaceIntegrationTest} passing
   * repeatedly at the unmodified production default pool size of 10 with this lock in place (see
   * that test's Javadoc).
   *
   * <p>This is a performance measure, not a correctness one: the database's partial unique indexes
   * (not this lock) remain the actual guarantee, unchanged and still exercised whenever this
   * lock-protected block is skipped or raced across multiple application instances - the lock is
   * process-local and does nothing for that case, deliberately: correctness must not depend on it.
   * {@link #PROVISIONING_LOCK_STRIPES} bounds the lock table's size; see its Javadoc for the
   * resulting trade-off. Concurrent first logins of <em>different</em> users still contend on the
   * connection pool exactly as before this lock (it only serializes same-user callers) - tracked
   * separately as #307, not addressed here.
   */
  private void ensurePersonalAssetsAfterCommit(UUID userId, UUID organizationId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              runEnsureBothPersonalAssetsUnderLock(userId, organizationId);
            }
          });
    } else {
      runEnsureBothPersonalAssetsUnderLock(userId, organizationId);
    }
  }

  /**
   * Acquires {@link #provisioningLockFor(UUID)} with a bounded {@code tryLock} instead of an
   * unbounded {@code lock()} (code review of #201/#305): {@link #ensureBothPersonalAssets} logs
   * rather than throws on failure (see its Javadoc), so a holder that hits two connection-acquire
   * timeouts back to back could otherwise hold the lock for up to roughly twice Hikari's {@code
   * connectionTimeout} (~60 s at the default) - and because {@link #PROVISIONING_LOCK_STRIPES} is a
   * fixed-size striping, that also blocks unrelated users sharing the same stripe, turning what
   * should be independent, parallel failures under a slow database into a serial pile-up of request
   * threads. Falling through without the lock on a failed {@code tryLock} is safe: correctness is
   * carried entirely by the database's partial unique indexes (see {@link
   * #ensurePersonalAssetsAfterCommit}'s Javadoc), never by this lock, so skipping it only forgoes
   * the connection-count reduction for this one call - the next login for the same user gets the
   * same idempotent, self-healing retry either way.
   */
  private void runEnsureBothPersonalAssetsUnderLock(UUID userId, UUID organizationId) {
    ReentrantLock lock = provisioningLockFor(userId);
    boolean acquired = false;
    try {
      acquired = lock.tryLock(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      ensureBothPersonalAssets(userId, organizationId);
    } finally {
      if (acquired) {
        lock.unlock();
      }
    }
  }

  private ReentrantLock provisioningLockFor(UUID userId) {
    int stripe = Math.floorMod(userId.hashCode(), provisioningLocks.length);
    return provisioningLocks[stripe];
  }

  /**
   * Attempts {@link SpaceService#ensureDefaultSpace} and {@link
   * KnowledgeLibraryService#ensurePersonalLibrary} independently of one another - the second call
   * always runs even if the first one throws, and vice versa. Neither call is wrapped in a
   * transaction of its own here - each of {@code ensureDefaultSpace}/{@code ensurePersonalLibrary}
   * already opens its own self-contained {@code REQUIRES_NEW} transaction (see their Javadoc), so
   * nesting one here would only add an unused, connection-holding transaction around calls that do
   * not need one - the exact class of cost the pool-exhaustion regression in #299 was caused by.
   *
   * <p><b>Failures are logged, not rethrown</b> (code review of #201/#305). An earlier version of
   * this method rethrew the first failure, reasoning that "an {@code afterCommit} callback failing
   * here only logs; it does not roll back the already-committed user creation transaction" - that
   * reasoning does not hold once {@link #findOrCreateUser} runs its no-ambient-transaction,
   * always-synchronous path (the normal one since #293/#299, not just a defensive fallback - see
   * {@link #ensurePersonalAssetsAfterCommit}'s Javadoc): there is no {@code afterCommit} callback
   * to swallow the exception on this path, and Spring propagates it straight to {@code
   * findOrCreateUser}'s caller, i.e. into the login request itself. Because this method is called
   * unconditionally on <em>every</em> {@link #findOrCreateUser} invocation (see below), a
   * persistently failing library or space provisioning would then fail every subsequent login for
   * that user too - turning a provisioning failure into a lockout, which is a worse outcome than
   * the login proceeding without (yet) having a personal space or library. Logging both failures
   * (if both occur) keeps them visible for operations without blocking the user.
   *
   * <p>Called unconditionally for every {@link #findOrCreateUser} invocation, not only for newly
   * created users: both {@code ensureDefaultSpace} and {@code ensurePersonalLibrary} are idempotent
   * (each checks for an existing row first), so a returning user whose personal library failed to
   * provision on an earlier login - or who predates #201 entirely - gets one created on their next
   * login instead of being left without one indefinitely, exactly because this method no longer
   * aborts that next login on the earlier failure. See #294, already open for the general
   * "idempotent provisioning must not become a lockout" concern this addresses for personal
   * space/library specifically.
   */
  private void ensureBothPersonalAssets(UUID userId, UUID organizationId) {
    try {
      spaceService.ensureDefaultSpace(userId, organizationId);
    } catch (RuntimeException e) {
      log.error(
          "Failed to provision personal space for user {} (organization {}); will retry on next"
              + " login",
          userId,
          organizationId,
          e);
    }

    try {
      libraryService.ensurePersonalLibrary(userId, organizationId);
    } catch (RuntimeException e) {
      log.error(
          "Failed to provision personal library for user {} (organization {}); will retry on next"
              + " login",
          userId,
          organizationId,
          e);
    }
  }

  public Optional<User> findBySubjectAndIssuer(String subject, String issuer) {
    return userRepository.findBySubjectAndIssuer(subject, issuer);
  }

  public Optional<User> findById(UUID id) {
    return userRepository.findById(id);
  }

  public List<User> findAll() {
    return userRepository.findAll();
  }

  /**
   * #392 code review, finding 3: the specification names "Erteilung und Entzug der
   * System-Admin-Rolle" explicitly in the first-stage event list, and this is the one method that
   * already performs it - {@code actorUserId} is the person making the change (the {@code
   * SYSTEM_ADMIN} caller {@code AdminController#changeRole} enforces via {@code @PreAuthorize}),
   * {@code userId}/{@code role} describe the change itself. {@code @Transactional} (already present
   * on this method beforehand) is what makes the audit write commit or roll back together with the
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
   */
  @Transactional
  public User updateRole(UUID userId, SystemRole role, UUID actorUserId) {
    User user =
        userRepository
            .findById(userId)
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
            actorUserId,
            pseudonym,
            AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED,
            previousRole,
            role);
      } else if (previousRole == SystemRole.AUDITOR) {
        recordRoleChange(
            saved, actorUserId, pseudonym, AuditEventType.AUDITOR_ROLE_REVOKED, previousRole, role);
      }
      if (role == SystemRole.SYSTEM_ADMIN) {
        recordRoleChange(
            saved,
            actorUserId,
            pseudonym,
            AuditEventType.SYSTEM_ADMIN_ROLE_GRANTED,
            previousRole,
            role);
      } else if (role == SystemRole.AUDITOR) {
        recordRoleChange(
            saved, actorUserId, pseudonym, AuditEventType.AUDITOR_ROLE_GRANTED, previousRole, role);
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
