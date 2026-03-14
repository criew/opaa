package io.opaa.auth;

import io.opaa.workspace.Workspace;
import io.opaa.workspace.WorkspaceMembership;
import io.opaa.workspace.WorkspaceProperties;
import io.opaa.workspace.WorkspaceRepository;
import io.opaa.workspace.WorkspaceRole;
import io.opaa.workspace.WorkspaceType;
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
@EnableConfigurationProperties({AuthProperties.class, WorkspaceProperties.class})
public class UserService {

  private static final String PERSONAL_WORKSPACE_NAME = "My Documents";
  private static final String PERSONAL_WORKSPACE_DESCRIPTION = "Private personal workspace";

  private final UserRepository userRepository;
  private final WorkspaceRepository workspaceRepository;
  private final AuthProperties authProperties;
  private final WorkspaceProperties workspaceProperties;

  public UserService(
      UserRepository userRepository,
      WorkspaceRepository workspaceRepository,
      AuthProperties authProperties,
      WorkspaceProperties workspaceProperties) {
    this.userRepository = userRepository;
    this.workspaceRepository = workspaceRepository;
    this.authProperties = authProperties;
    this.workspaceProperties = workspaceProperties;
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
                  if (isInitialAdmin(email)) {
                    newUser.setSystemRole(SystemRole.SYSTEM_ADMIN);
                  }
                  return userRepository.save(newUser);
                });

    ensurePersonalWorkspace(user);
    ensureDefaultWorkspace(user);
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
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
    user.setSystemRole(role);
    return userRepository.save(user);
  }

  private boolean isInitialAdmin(String email) {
    String initialAdminEmail = authProperties.initialAdminEmail();
    return initialAdminEmail != null
        && !initialAdminEmail.isBlank()
        && initialAdminEmail.equalsIgnoreCase(email);
  }

  private void ensureDefaultWorkspace(User user) {
    if (user.getSystemRole() != SystemRole.SYSTEM_ADMIN) {
      return;
    }
    String defaultName = workspaceProperties.defaultWorkspace().name();
    if (workspaceRepository.existsByNameIgnoreCase(defaultName)) {
      return;
    }
    Workspace defaultWorkspace =
        new Workspace(
            defaultName,
            workspaceProperties.defaultWorkspace().description(),
            WorkspaceType.SHARED,
            user.getId());
    defaultWorkspace.addMembership(new WorkspaceMembership(user.getId(), WorkspaceRole.OWNER));
    workspaceRepository.save(defaultWorkspace);
  }

  private void ensurePersonalWorkspace(User user) {
    if (workspaceRepository.existsByOwnerIdAndType(user.getId(), WorkspaceType.PERSONAL)) {
      return;
    }

    Workspace personalWorkspace =
        new Workspace(
            PERSONAL_WORKSPACE_NAME,
            PERSONAL_WORKSPACE_DESCRIPTION,
            WorkspaceType.PERSONAL,
            user.getId());
    personalWorkspace.addMembership(new WorkspaceMembership(user.getId(), WorkspaceRole.OWNER));
    workspaceRepository.save(personalWorkspace);
  }
}
