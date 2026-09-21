package io.opaa.permission;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a grant path needs to know about a group without knowing {@code io.opaa.group} - the port
 * that lets {@code io.opaa.library} validate and name a grant subject after the mutual dependency
 * {@code library} &harr; {@code group} was resolved (ADR-0036, Entscheidung 12). Implemented by
 * {@code io.opaa.group.GroupSubjectDirectoryAdapter}.
 */
public interface GroupSubjectDirectory {

  /** The group, or empty if no group carries that id - never filtered by organization here. */
  Optional<GroupSubject> find(UUID groupId);

  /**
   * Whether {@code userId} may name this group as a grant subject at all (ADR-0036, Entscheidung
   * 9). An internal group that its stewards have not released for use is invisible to everyone but
   * its own members, its stewards and a system administrator - for anybody else it answers like a
   * group that does not exist. Asked on <b>every</b> path that accepts a group id, including the
   * one where a caller types the id by hand: in the selection list alone the rule would be
   * cosmetics.
   */
  boolean isSelectableBy(UUID groupId, UUID userId, boolean systemAdmin);

  /** The display names of the given groups; an id with no group is absent from the result. */
  Map<UUID, String> namesById(Collection<UUID> groupIds);
}
