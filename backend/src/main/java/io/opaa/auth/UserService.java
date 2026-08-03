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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile({"oidc", "basic"})
@EnableConfigurationProperties(AuthProperties.class)
public class UserService {

  private final UserRepository userRepository;
  private final SpaceService spaceService;
  private final AuthProperties authProperties;
  private final TransactionTemplate requiresNewTransactionTemplate;

  public UserService(
      UserRepository userRepository,
      SpaceService spaceService,
      AuthProperties authProperties,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.spaceService = spaceService;
    this.authProperties = authProperties;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
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
   * <p>{@code findOrCreateUser} is itself {@code @Transactional}. On Postgres, a failed statement
   * aborts the entire enclosing transaction, so catching a {@link DataIntegrityViolationException}
   * from an insert made on that same connection/transaction would leave every subsequent statement
   * in {@code findOrCreateUser} - including the read-back of the winner's row below - failing too.
   * The insert therefore runs in its own {@code REQUIRES_NEW} transaction, on its own connection: a
   * constraint violation there rolls back only that attempt and leaves the caller's transaction
   * untouched, so the loser can simply read the row the winner has by now committed, instead of
   * surfacing a 500 for {@code uq_users_subject_issuer}. Same pattern and reasoning as {@code
   * SpaceService#ensurePersonalSpace}.
   *
   * <p>Unlike {@code ensurePersonalSpace}, this does not need to be deferred to {@code
   * TransactionSynchronization#afterCommit()} (the fix required for #280/#287): the {@code
   * REQUIRES_NEW} insert here does not depend on anything the caller's still-open transaction has
   * written, so there is no visibility problem to work around - it only needs its own row to be
   * unique, which Postgres enforces regardless of what else is uncommitted on other connections.
   */
  private User createOrFetchUser(String subject, String issuer, String email, String displayName) {
    try {
      return requiresNewTransactionTemplate.execute(
          status -> insertUser(subject, issuer, email, displayName));
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
    // saveAndFlush forces the INSERT to execute (and thus to fail, if it must) inside this
    // REQUIRES_NEW transaction, instead of being deferred to a later flush point outside of it.
    return userRepository.saveAndFlush(newUser);
  }

  /**
   * Runs {@link SpaceService#ensurePersonalSpace} only after this method's own transaction has
   * committed the {@code users} row.
   *
   * <p>{@code findOrCreateUser} is {@code @Transactional} and inserts the new user in a still-open
   * transaction. {@code ensurePersonalSpace} inserts the personal space in its own {@code
   * REQUIRES_NEW} transaction (see its Javadoc), which runs on a separate connection with its own
   * snapshot - a call from inside the still-open outer transaction cannot see the uncommitted
   * {@code users} row there, so the insert violated {@code fk_spaces_owner} and the whole login
   * failed (regression from #265, fixed in #280 follow-up). Deferring the call to {@link
   * TransactionSynchronization#afterCommit()} guarantees the user row is already committed and
   * visible on any connection by the time the personal space is created.
   *
   * <p>Falls back to an immediate, synchronous call when no transaction synchronization is active -
   * this keeps {@code UserServiceTest} working without a real Spring-managed transaction, and would
   * also apply if this method were ever called outside of a transactional context.
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
