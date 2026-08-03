package io.opaa.auth;

import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

  private final UserRepository userRepository;
  private final SpaceService spaceService;
  private final AuthProperties authProperties;

  public UserService(
      UserRepository userRepository, SpaceService spaceService, AuthProperties authProperties) {
    this.userRepository = userRepository;
    this.spaceService = spaceService;
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
   */
  public User findOrCreateUser(String subject, String issuer, String email, String displayName) {
    User user =
        userRepository
            .findBySubjectAndIssuer(subject, issuer)
            .map(existing -> updateExistingUser(existing, email, displayName))
            .orElseGet(() -> createOrFetchUser(subject, issuer, email, displayName));

    ensurePersonalSpaceAfterCommit(user.getId(), user.getOrganizationId());
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
   * Runs {@link SpaceService#ensurePersonalSpace} only after the {@code users} row it needs has
   * been committed.
   *
   * <p>Historically (#265, fixed in the #280 follow-up), {@code findOrCreateUser} was
   * {@code @Transactional} and inserted the new user in a still-open transaction; {@code
   * ensurePersonalSpace} inserts the personal space in its own {@code REQUIRES_NEW} transaction
   * (see its Javadoc), on a separate connection with its own snapshot that could not see the
   * uncommitted {@code users} row, so the insert violated {@code fk_spaces_owner} and the whole
   * login failed. Deferring the call to {@link TransactionSynchronization#afterCommit()} fixed that
   * by guaranteeing the user row was already committed and visible by the time the personal space
   * was created.
   *
   * <p>Since {@link #findOrCreateUser} was made deliberately non-{@code @Transactional} (#293 code
   * review - see its Javadoc), there is no ambient transaction synchronization active here to
   * register a callback with in the first place: every call below now always takes the immediate,
   * synchronous branch. That remains correct for the same reason the {@code afterCommit} deferral
   * did - each {@link UserRepository} call in {@code findOrCreateUser} already committed
   * independently by the time control reaches here, so the user row this method's caller passes in
   * is always already visible on any connection, including {@code ensurePersonalSpace}'s {@code
   * REQUIRES_NEW} one.
   */
  private void ensurePersonalSpaceAfterCommit(UUID userId, UUID organizationId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              spaceService.ensurePersonalSpace(userId, organizationId);
            }
          });
    } else {
      spaceService.ensurePersonalSpace(userId, organizationId);
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
