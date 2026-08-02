package io.opaa.auth;

import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
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

  @Transactional
  public User findOrCreateUser(String subject, String issuer, String email, String displayName) {
    User user =
        userRepository
            .findBySubjectAndIssuer(subject, issuer)
            .map(
                existing -> {
                  existing.setLastLoginAt(Instant.now());
                  if (email != null) {
                    existing.setEmail(email);
                  }
                  if (displayName != null) {
                    existing.setDisplayName(displayName);
                  }
                  return userRepository.save(existing);
                })
            .orElseGet(
                () -> {
                  User newUser = new User(subject, issuer, email, displayName);
                  newUser.setOrganizationId(Organization.DEFAULT_ID);
                  if (isInitialAdmin(email)) {
                    newUser.setSystemRole(SystemRole.SYSTEM_ADMIN);
                  }
                  return userRepository.save(newUser);
                });

    ensurePersonalSpaceAfterCommit(user.getId(), user.getOrganizationId());
    return user;
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
