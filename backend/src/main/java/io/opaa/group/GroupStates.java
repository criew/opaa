package io.opaa.group;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupMechanism;
import io.opaa.api.types.GroupState;

/**
 * The one definition of whether a group is in effect (#1978, ADR-0036 Entscheidungen 2 and 3),
 * shared by the list, its filter and sort, and the subject selection. {@code provider} is null
 * exactly for an internal group.
 */
public final class GroupStates {

  private GroupStates() {}

  /** A token group of a provider that now delivers its groups through the directory sync. */
  static boolean unmaintained(Group group, GroupProviderView provider) {
    return provider != null
        && group.getKind() == GroupKind.IDENTITY_PROVIDER
        && provider.mechanism() == GroupMechanism.DIRECTORY;
  }

  /** The first condition that applies, in the order {@link GroupState} declares them. */
  public static GroupState stateOf(Group group, GroupProviderView provider) {
    if (group.isDissolved()) {
      return GroupState.DISSOLVED;
    }
    if (provider != null && !provider.enabled()) {
      return GroupState.PROVIDER_DISABLED;
    }
    if (unmaintained(group, provider)) {
      return GroupState.UNMAINTAINED;
    }
    if (group.getKind() == GroupKind.AD_HOC && !group.isSelectableAsSubject()) {
      return GroupState.NOT_RELEASED;
    }
    return GroupState.ACTIVE;
  }
}
