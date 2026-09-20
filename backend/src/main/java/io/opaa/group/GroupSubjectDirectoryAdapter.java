package io.opaa.group;

import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupSubjectDirectory} from {@link GroupRepository} - the one place a grant path
 * outside this package learns anything about a group, so {@code io.opaa.library} no longer holds a
 * {@link Group} or its repository (ADR-0036, Entscheidung 12).
 */
@Component
class GroupSubjectDirectoryAdapter implements GroupSubjectDirectory {

  private final GroupRepository groupRepository;

  GroupSubjectDirectoryAdapter(GroupRepository groupRepository) {
    this.groupRepository = groupRepository;
  }

  @Override
  public Optional<GroupSubject> find(UUID groupId) {
    return groupRepository
        .findById(groupId)
        .map(
            group ->
                new GroupSubject(
                    group.getId(),
                    group.getOrganizationId(),
                    group.getName(),
                    group.isDissolved()));
  }

  @Override
  public Map<UUID, String> namesById(Collection<UUID> groupIds) {
    Map<UUID, String> names = new HashMap<>();
    for (Group group : groupRepository.findAllById(groupIds)) {
      names.put(group.getId(), group.getName());
    }
    return names;
  }
}
