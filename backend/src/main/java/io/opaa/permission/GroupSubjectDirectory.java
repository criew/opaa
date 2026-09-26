package io.opaa.permission;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a grant path needs to know about a group without knowing {@code io.opaa.group} - the port
 * that lets {@code io.opaa.library} validate and name a grant subject after the mutual dependency
 * {@code library} &harr; {@code group} was resolved (ADR-0036, Entscheidung 12). Implemented by
 * {@code GroupSubjectDirectoryAdapter}.
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

  /**
   * The display names of the given groups; an id with no group is absent from the result. The raw
   * name, protection included - for the paths that are allowed to read it (the Stichtagsauskunft of
   * the Aufsicht, an audit entry, the group's own administration).
   */
  Map<UUID, String> namesById(Collection<UUID> groupIds);

  /**
   * The names for a list a third party reads: a protected group appears as {@link
   * #PROTECTED_GROUP_LABEL}, never under its own name (ADR-0036, Entscheidung 9). Every other group
   * answers exactly as {@link #namesById}.
   */
  Map<UUID, String> displayNamesById(Collection<UUID> groupIds);

  /** What a protected group is called where its name is withheld. */
  String PROTECTED_GROUP_LABEL = "Geschützte Gruppe";

  /**
   * The groups as the Herleitung names them (#1822) - name, origin, provider and mechanism in one
   * lookup for the whole set. An id with no group is absent from the result: a group deleted since
   * the grant was written has no attribution left to give, and the Herleitung says so by omission
   * rather than by inventing a name.
   */
  Map<UUID, GroupAttribution> attributionsById(Collection<UUID> groupIds);
}
