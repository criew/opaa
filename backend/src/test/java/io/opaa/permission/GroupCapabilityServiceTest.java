package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * "Handlungsfähige Gruppe" as ADR-0036 defines it: effective <b>and</b> reaching at least one
 * active account. The matrix lives here rather than at a caller - a group that can act is one
 * measure, and the space policy, the library's owner and the operational list all read it from
 * here.
 */
class GroupCapabilityServiceTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final GroupSubjectDirectory directory = mock(GroupSubjectDirectory.class);
  private final GroupMembershipResolver memberships = mock(GroupMembershipResolver.class);
  private final GroupCapabilityService service = new GroupCapabilityService(directory, memberships);

  /**
   * (dissolved, provider disabled, unmaintained, active accounts, can act) - the three reasons a
   * group is not effective (the third added by #1816) plus the account requirement.
   */
  private static Stream<Arguments> capability() {
    return Stream.of(
        Arguments.of(false, false, false, 3, true),
        Arguments.of(false, false, false, 0, false),
        Arguments.of(true, false, false, 3, false),
        Arguments.of(false, true, false, 3, false),
        Arguments.of(false, false, true, 3, false));
  }

  @ParameterizedTest
  @MethodSource("capability")
  void aGroupCanActOnlyWhileItIsEffectiveAndReachesAnActiveAccount(
      boolean dissolved,
      boolean providerDisabled,
      boolean unmaintained,
      int activeMembers,
      boolean expected) {
    UUID group = UUID.randomUUID();
    when(directory.find(group))
        .thenReturn(
            Optional.of(
                new GroupSubject(
                    group,
                    ORGANIZATION,
                    "Referat 50",
                    dissolved,
                    providerDisabled,
                    unmaintained,
                    false,
                    true)));
    when(memberships.activeMemberCount(eq(group), any())).thenReturn(activeMembers);

    assertThat(service.isCapable(group)).isEqualTo(expected);
  }

  /** An effective group may be empty - it just cannot act, which is a different question. */
  @Test
  void anEmptyGroupIsEffectiveButCannotAct() {
    GroupSubject empty =
        new GroupSubject(
            UUID.randomUUID(), ORGANIZATION, "Referat 52", false, false, false, false, true);
    when(memberships.activeMemberCount(eq(empty.id()), any())).thenReturn(0);

    assertThat(service.isEffective(empty)).isTrue();
    assertThat(service.isCapable(empty)).isFalse();
  }

  /** A group nobody can resolve any more can act as little as a dissolved one. */
  @Test
  void anUnknownGroupCannotAct() {
    UUID gone = UUID.randomUUID();
    when(directory.find(gone)).thenReturn(Optional.empty());

    assertThat(service.isCapable(gone)).isFalse();
  }
}
