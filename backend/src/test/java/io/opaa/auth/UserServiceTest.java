package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;

  @InjectMocks private UserService userService;

  @Test
  void findOrCreateUserCreatesNewUserWhenNotExisting() {
    when(userRepository.findBySubjectAndIssuer("sub-1", "issuer-1")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User result = userService.findOrCreateUser("sub-1", "issuer-1", "user@opaa.local", "User");

    assertThat(result.getSubject()).isEqualTo("sub-1");
    assertThat(result.getIssuer()).isEqualTo("issuer-1");
    assertThat(result.getEmail()).isEqualTo("user@opaa.local");
    assertThat(result.getDisplayName()).isEqualTo("User");
    assertThat(result.getCreatedAt()).isNotNull();
    assertThat(result.getLastLoginAt()).isNotNull();
    verify(userRepository).save(any(User.class));
  }

  @Test
  void findOrCreateUserUpdatesExistingUserDataAndLastLogin() {
    User existing = new User("sub-1", "issuer-1", "old@opaa.local", "Old Name");
    Instant beforeUpdate = existing.getLastLoginAt();

    when(userRepository.findBySubjectAndIssuer("sub-1", "issuer-1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(existing)).thenReturn(existing);

    User result = userService.findOrCreateUser("sub-1", "issuer-1", "new@opaa.local", "New Name");

    assertThat(result.getEmail()).isEqualTo("new@opaa.local");
    assertThat(result.getDisplayName()).isEqualTo("New Name");
    assertThat(result.getLastLoginAt()).isAfterOrEqualTo(beforeUpdate);
    verify(userRepository).save(existing);
  }
}
