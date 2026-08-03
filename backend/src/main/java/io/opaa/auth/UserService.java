package io.opaa.auth;

import io.opaa.library.KnowledgeLibraryService;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Profile({"oidc", "basic"})
@EnableConfigurationProperties(AuthProperties.class)
public class UserService {

  private static final Logger log = LoggerFactory.getLogger(UserService.class);

  private final UserRepository userRepository;
  private final SpaceService spaceService;
  private final KnowledgeLibraryService libraryService;
  private final AuthProperties authProperties;

  public UserService(
      UserRepository userRepository,
      SpaceService spaceService,
      KnowledgeLibraryService libraryService,
      AuthProperties authProperties) {
    this.userRepository = userRepository;
    this.spaceService = spaceService;
    this.libraryService = libraryService;
    this.authProperties = authProperties;
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
   * provisioning) follows the exact same reasoning: neither {@code ensurePersonalSpace} nor {@code
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
   * SpaceService#ensurePersonalSpace}, but without that method's {@code REQUIRES_NEW} - there is no
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
   * Runs {@link SpaceService#ensurePersonalSpace} and {@link
   * KnowledgeLibraryService#ensurePersonalLibrary} only after the {@code users} row they need has
   * been committed - and always together, so provisioning never silently produces a personal space
   * without its personal library or the other way round (#201's "creating a user creates a personal
   * space and a personal library atomically").
   *
   * <p>Historically (#265, fixed in the #280 follow-up), {@code findOrCreateUser} was
   * {@code @Transactional} and inserted the new user in a still-open transaction; {@code
   * ensurePersonalSpace} inserts the personal space in its own {@code REQUIRES_NEW} transaction
   * (see its Javadoc), on a separate connection with its own snapshot that could not see the
   * uncommitted {@code users} row, so the insert violated {@code fk_spaces_owner} and the whole
   * login failed. Deferring the call to {@link TransactionSynchronization#afterCommit()} fixed that
   * by guaranteeing the user row was already committed and visible by the time the personal space
   * was created. {@code ensurePersonalLibrary} (#201) inserts in its own {@code REQUIRES_NEW}
   * transaction the same way and is subject to exactly the same visibility requirement, so it is
   * called from this same method rather than from a second, parallel deferral mechanism.
   *
   * <p>Since {@link #findOrCreateUser} was made deliberately non-{@code @Transactional} (#293 code
   * review - see its Javadoc), there is no ambient transaction synchronization active here to
   * register a callback with in the first place: every call below now always takes the immediate,
   * synchronous branch. That remains correct for the same reason the {@code afterCommit} deferral
   * did - each {@link UserRepository} call in {@code findOrCreateUser} already committed
   * independently by the time control reaches here, so the user row this method's caller passes in
   * is always already visible on any connection, including {@code ensurePersonalSpace}'s and {@code
   * ensurePersonalLibrary}'s {@code REQUIRES_NEW} ones. The {@code isSynchronizationActive}/{@code
   * registerSynchronization} branch below is kept only as a defensive fallback for a caller running
   * inside its own transaction (there is none in production today) - it must not silently skip
   * provisioning if one ever exists.
   */
  private void ensurePersonalAssetsAfterCommit(UUID userId, UUID organizationId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              ensureBothPersonalAssets(userId, organizationId);
            }
          });
    } else {
      ensureBothPersonalAssets(userId, organizationId);
    }
  }

  /**
   * Attempts {@link SpaceService#ensurePersonalSpace} and {@link
   * KnowledgeLibraryService#ensurePersonalLibrary} independently of one another - the second call
   * always runs even if the first one throws, and vice versa. Neither call is wrapped in a
   * transaction of its own here - each of {@code ensurePersonalSpace}/{@code ensurePersonalLibrary}
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
   * created users: both {@code ensurePersonalSpace} and {@code ensurePersonalLibrary} are
   * idempotent (each checks for an existing row first), so a returning user whose personal library
   * failed to provision on an earlier login - or who predates #201 entirely - gets one created on
   * their next login instead of being left without one indefinitely, exactly because this method no
   * longer aborts that next login on the earlier failure. See #294, already open for the general
   * "idempotent provisioning must not become a lockout" concern this addresses for personal
   * space/library specifically.
   */
  private void ensureBothPersonalAssets(UUID userId, UUID organizationId) {
    try {
      spaceService.ensurePersonalSpace(userId, organizationId);
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

  @Transactional
  public User updateRole(UUID userId, SystemRole role) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Benutzer nicht gefunden: " + userId));
    user.setSystemRole(role);
    return userRepository.save(user);
  }

  private boolean isInitialAdmin(String email) {
    String initialAdminEmail = authProperties.initialAdminEmail();
    return initialAdminEmail != null
        && !initialAdminEmail.isBlank()
        && initialAdminEmail.equalsIgnoreCase(email);
  }
}
