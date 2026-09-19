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

  /** The display names of the given groups; an id with no group is absent from the result. */
  Map<UUID, String> namesById(Collection<UUID> groupIds);
}
