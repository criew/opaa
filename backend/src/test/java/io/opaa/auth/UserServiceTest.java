package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;

  @Mock private SpaceService spaceService;

  @Mock private AuthProperties authProperties;

  @InjectMocks private UserService userService;

  @Test
  void findOrCreateUserCreatesNewUser() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn(null);

    User user = userService.findOrCreateUser("sub1", "issuer1", "test@example.com", "Test");

    assertThat(user.getSubject()).isEqualTo("sub1");
    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
    assertThat(user.getOrganizationId()).isEqualTo(Organization.DEFAULT_ID);
    verify(spaceService).ensurePersonalSpace(user.getId(), Organization.DEFAULT_ID);
  }

  @Test
  void findOrCreateUserUpdatesExistingUser() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User user = userService.findOrCreateUser("sub1", "issuer1", "new@example.com", "New Name");

    assertThat(user.getEmail()).isEqualTo("new@example.com");
    assertThat(user.getDisplayName()).isEqualTo("New Name");
    verify(spaceService).ensurePersonalSpace(existing.getId(), Organization.DEFAULT_ID);
  }

  @Test
  void findOrCreateUserGrantsSystemAdminToInitialAdmin() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    User user = userService.findOrCreateUser("sub1", "issuer1", "admin@example.com", "Admin");

    assertThat(user.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void findOrCreateUserDoesNotGrantAdminForNonMatchingEmail() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
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
  void findOrCreateUserDelegatesPersonalSpaceIdempotencyToSpaceService() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    userService.findOrCreateUser("sub1", "issuer1", "old@example.com", "Old Name");

    // UserService no longer checks existence itself; SpaceService.ensurePersonalSpace is
    // idempotent and is always called, whether the user is new or existing.
    verify(spaceService).ensurePersonalSpace(existing.getId(), Organization.DEFAULT_ID);
  }
}
