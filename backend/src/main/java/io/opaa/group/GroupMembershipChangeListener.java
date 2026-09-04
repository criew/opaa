package io.opaa.group;

import java.util.Collection;

/**
 * Told whenever a person's group set changed as far as this process knows it - the moment {@link
 * GroupMembershipResolver} evicts that person's cached groups. Anything keyed on a person's rights
 * (a rights-scoped aggregate, #1070) hangs its own eviction here rather than on each of the call
 * sites that change memberships, so a new membership path cannot forget it.
 */
public interface GroupMembershipChangeListener {

  /** The persons whose group set changed; called after the change is committed. */
  void onMembershipChanged(Collection<java.util.UUID> userIds);
}
