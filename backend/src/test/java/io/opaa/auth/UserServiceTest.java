package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.workspace.Workspace;
import io.opaa.workspace.WorkspaceProperties;
import io.opaa.workspace.WorkspaceRepository;
import io.opaa.workspace.WorkspaceType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;

  @Mock private WorkspaceRepository workspaceRepository;

  @Mock private AuthProperties authProperties;

  private final WorkspaceProperties workspaceProperties =
      new WorkspaceProperties(
          new WorkspaceProperties.DefaultWorkspace("Default", "Default shared workspace"));

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService =
        new UserService(userRepository, workspaceRepository, authProperties, workspaceProperties);
  }

  @Test
  void findOrCreateUserCreatesNewUser() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(any(UUID.class), any(WorkspaceType.class)))
        .thenReturn(false);
    when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn(null);

    User user = userService.findOrCreateUser("sub1", "issuer1", "test@example.com", "Test");

    assertThat(user.getSubject()).isEqualTo("sub1");
    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
    verify(workspaceRepository).save(any(Workspace.class));
  }

  @Test
  void findOrCreateUserUpdatesExistingUser() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(existing.getId(), WorkspaceType.PERSONAL))
        .thenReturn(true);

    User user = userService.findOrCreateUser("sub1", "issuer1", "new@example.com", "New Name");

    assertThat(user.getEmail()).isEqualTo("new@example.com");
    assertThat(user.getDisplayName()).isEqualTo("New Name");
    verify(workspaceRepository, never()).save(any(Workspace.class));
  }

  @Test
  void findOrCreateUserGrantsSystemAdminToInitialAdmin() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(any(UUID.class), any(WorkspaceType.class)))
        .thenReturn(false);
    when(workspaceRepository.existsByNameIgnoreCase(anyString())).thenReturn(false);
    when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    User user = userService.findOrCreateUser("sub1", "issuer1", "admin@example.com", "Admin");

    assertThat(user.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void findOrCreateUserDoesNotGrantAdminForNonMatchingEmail() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(any(UUID.class), any(WorkspaceType.class)))
        .thenReturn(false);
    when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    User user = userService.findOrCreateUser("sub1", "issuer1", "other@example.com", "Other");

    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
  }

  @Test
  void updateRoleChangesUserRole() {
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User updated = userService.updateRole(userId, SystemRole.SYSTEM_ADMIN);

    assertThat(updated.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void updateRoleThrowsForNonexistentUser() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userService.updateRole(userId, SystemRole.SYSTEM_ADMIN))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void findOrCreateUserDoesNotCreateDuplicatePersonalWorkspace() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(existing.getId(), WorkspaceType.PERSONAL))
        .thenReturn(true);

    userService.findOrCreateUser("sub1", "issuer1", "old@example.com", "Old Name");

    verify(workspaceRepository, never()).save(any(Workspace.class));
  }

  @Test
  void firstLoginOfSystemAdminCreatesDefaultWorkspace() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(any(UUID.class), any(WorkspaceType.class)))
        .thenReturn(false);
    when(workspaceRepository.existsByNameIgnoreCase("Default")).thenReturn(false);
    when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    userService.findOrCreateUser("sub1", "issuer1", "admin@example.com", "Admin");

    // Saves both personal workspace and default workspace
    verify(workspaceRepository, times(2)).save(any(Workspace.class));
  }

  @Test
  void defaultWorkspaceNotCreatedIfAlreadyExists() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(any(UUID.class), any(WorkspaceType.class)))
        .thenReturn(false);
    when(workspaceRepository.existsByNameIgnoreCase("Default")).thenReturn(true);
    when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    userService.findOrCreateUser("sub1", "issuer1", "admin@example.com", "Admin");

    // Only personal workspace is saved; default already exists
    verify(workspaceRepository, times(1)).save(any(Workspace.class));
  }

  @Test
  void regularUserLoginDoesNotCreateDefaultWorkspace() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workspaceRepository.existsByOwnerIdAndType(any(UUID.class), any(WorkspaceType.class)))
        .thenReturn(false);
    when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    userService.findOrCreateUser("sub1", "issuer1", "user@example.com", "User");

    // Only personal workspace is saved; no default workspace for regular users
    verify(workspaceRepository, times(1)).save(any(Workspace.class));
    verify(workspaceRepository, never()).existsByNameIgnoreCase(anyString());
  }
}
