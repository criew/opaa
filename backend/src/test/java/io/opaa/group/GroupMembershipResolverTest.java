package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

  private GroupMembershipResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new GroupMembershipResolver(membershipRepository);
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
  void resolveUserIdsForAUserSubjectReturnsExactlyThatUserWithoutQueryingTheRepository() {
    UUID userId = UUID.randomUUID();

    Set<UUID> resolved = resolver.resolveUserIds(PermissionSubject.user(userId));

    assertThat(resolved).containsExactly(userId);
  }

  @Test
  void resolveUserIdsForAGroupSubjectReturnsItsMembers() {
    UUID groupId = UUID.randomUUID();
    UUID memberOne = UUID.randomUUID();
    UUID memberTwo = UUID.randomUUID();
    when(membershipRepository.findUserIdsByGroupId(groupId))
        .thenReturn(Set.of(memberOne, memberTwo));

    Set<UUID> resolved = resolver.resolveUserIds(PermissionSubject.group(groupId));

    assertThat(resolved).containsExactlyInAnyOrder(memberOne, memberTwo);
  }
}
