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

    spaceService.ensurePersonalSpace(user.getId(), user.getOrganizationId());
    return user;
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
