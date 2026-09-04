package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupMembershipResolverTest {

  @Mock private GroupMembershipRepository membershipRepository;
  @Mock private UserRepository userRepository;

  private GroupMembershipResolver resolver;

  @BeforeEach
  void setUp() {
    resolver =
        new GroupMembershipResolver(
            membershipRepository,
            userRepository,
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(io.opaa.group.GroupMembershipChangeListener.class));
  }

  @Test
  void groupIdsForUserIsCachedAcrossRepeatedCalls() {
    UUID user = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    when(membershipRepository.findGroupIdsByUserId(user)).thenReturn(Set.of(group));

    Set<UUID> first = resolver.groupIdsForUser(user);
    Set<UUID> second = resolver.groupIdsForUser(user);

    assertThat(first).containsExactly(group);
    assertThat(second).containsExactly(group);
    verify(membershipRepository, times(1)).findGroupIdsByUserId(user);
  }

  @Test
  void invalidateUserForcesTheNextCallToHitTheRepositoryAgain() {
    UUID user = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    when(membershipRepository.findGroupIdsByUserId(user)).thenReturn(Set.of(group), Set.of());

    Set<UUID> before = resolver.groupIdsForUser(user);
    resolver.invalidateUser(user);
    Set<UUID> after = resolver.groupIdsForUser(user);

    assertThat(before).containsExactly(group);
    assertThat(after).isEmpty();
    verify(membershipRepository, times(2)).findGroupIdsByUserId(user);
  }

  @Test
  void invalidateUsersEvictsMultipleUsersAtOnce() {
    UUID userOne = UUID.randomUUID();
    UUID userTwo = UUID.randomUUID();
    when(membershipRepository.findGroupIdsByUserId(userOne)).thenReturn(Set.of());
    when(membershipRepository.findGroupIdsByUserId(userTwo)).thenReturn(Set.of());
    resolver.groupIdsForUser(userOne);
    resolver.groupIdsForUser(userTwo);

    resolver.invalidateUsers(Set.of(userOne, userTwo));
    resolver.groupIdsForUser(userOne);
    resolver.groupIdsForUser(userTwo);

    verify(membershipRepository, times(2)).findGroupIdsByUserId(userOne);
    verify(membershipRepository, times(2)).findGroupIdsByUserId(userTwo);
  }

  @Test
  void resolveUserIdsForAUserSubjectInTheSameOrganizationReturnsExactlyThatUser() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    User user = new User("sub", "issuer", "u@example.com", "User");
    user.setOrganizationId(organizationId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    Set<UUID> resolved = resolver.resolveUserIds(PermissionSubject.user(userId, organizationId));

    assertThat(resolved).containsExactly(userId);
  }

  @Test
  void resolveUserIdsForAUserSubjectFromAnotherOrganizationResolvesToNobody() {
    UUID userId = UUID.randomUUID();
    UUID actualOrganizationId = UUID.randomUUID();
    UUID claimedOrganizationId = UUID.randomUUID();
    User user = new User("sub", "issuer", "u@example.com", "User");
    user.setOrganizationId(actualOrganizationId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    // A subject that names this user but the wrong organization must not resolve to them -
    // otherwise the USER branch would be the unguarded exception to the boundary the GROUP
    // branch enforces.
    Set<UUID> resolved =
        resolver.resolveUserIds(PermissionSubject.user(userId, claimedOrganizationId));

    assertThat(resolved).isEmpty();
  }

  @Test
  void resolveUserIdsForANonExistentUserSubjectResolvesToNobody() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    Set<UUID> resolved = resolver.resolveUserIds(PermissionSubject.user(userId, organizationId));

    assertThat(resolved).isEmpty();
  }

  @Test
  void resolveUserIdsForAGroupSubjectReturnsItsMembersScopedToTheOrganization() {
    UUID groupId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    UUID memberOne = UUID.randomUUID();
    UUID memberTwo = UUID.randomUUID();
    when(membershipRepository.findUserIdsByGroupIdAndOrganizationId(groupId, organizationId))
        .thenReturn(Set.of(memberOne, memberTwo));

    Set<UUID> resolved = resolver.resolveUserIds(PermissionSubject.group(groupId, organizationId));

    assertThat(resolved).containsExactlyInAnyOrder(memberOne, memberTwo);
  }

  @Test
  void resolveUserIdsForAGroupSubjectFromAnotherOrganizationResolvesToNobody() {
    UUID groupId = UUID.randomUUID();
    UUID otherOrganizationId = UUID.randomUUID();
    // The repository itself filters by organization; returning an empty set for the wrong
    // organizationId here simulates that and shows resolveUserIds passes the subject's
    // organizationId through rather than trusting the group id alone.
    when(membershipRepository.findUserIdsByGroupIdAndOrganizationId(groupId, otherOrganizationId))
        .thenReturn(Set.of());

    Set<UUID> resolved =
        resolver.resolveUserIds(PermissionSubject.group(groupId, otherOrganizationId));

    assertThat(resolved).isEmpty();
  }
}
