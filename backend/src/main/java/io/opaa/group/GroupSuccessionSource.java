package io.opaa.group;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.auth.AccountActivityService;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An internal group without an active steward (#1819, ADR-0036 Entscheidung 6) - including the
 * groups the migration of #1812 turned into internal ones <b>without</b> stewards: exactly the
 * state that changeset 041 left behind on purpose, to be picked up here. A provider group never
 * counts: it is maintained where it comes from, and its protection mark is the system
 * administration's to decide.
 */
@Component
class GroupSuccessionSource implements SuccessionFindingSource {

  private final GroupRepository groups;
  private final GroupStewardRepository stewards;
  private final AccountActivityService accountActivity;

  GroupSuccessionSource(
      GroupRepository groups,
      GroupStewardRepository stewards,
      AccountActivityService accountActivity) {
    this.groups = groups;
    this.stewards = stewards;
    this.accountActivity = accountActivity;
  }

  @Override
  public SuccessionKind kind() {
    return SuccessionKind.OPEN_SUCCESSION;
  }

  @Override
  public boolean answersFor(SuccessionObjectType objectType) {
    return objectType == SuccessionObjectType.GROUP;
  }

  /**
   * Three queries for the whole organization - its groups, the stewards of every internal one, and
   * one account query over all of them - rather than that pair per group (#682's rule).
   */
  @Override
  public List<SuccessionFinding> findingsOf(UUID organizationId) {
    return openAmong(groups.findByOrganizationId(organizationId));
  }

  @Override
  public Optional<SuccessionFinding> findingFor(UUID groupId) {
    return groups
        .findById(groupId)
        .map(group -> openAmong(List.of(group)))
        .orElse(List.of())
        .stream()
        .findFirst();
  }

  /** The one derivation both entry points read. */
  private List<SuccessionFinding> openAmong(Collection<Group> candidates) {
    List<Group> relevant = candidates.stream().filter(Group::isInternal).toList();
    if (relevant.isEmpty()) {
      return List.of();
    }
    List<UUID> groupIds = relevant.stream().map(Group::getId).toList();
    Map<UUID, List<UUID>> stewardsByGroup = new HashMap<>();
    for (GroupSteward steward : stewards.findByGroupIdIn(groupIds)) {
      stewardsByGroup
          .computeIfAbsent(steward.getGroupId(), key -> new ArrayList<>())
          .add(steward.getUserId());
    }
    Set<UUID> active =
        accountActivity.activeAmong(
            stewardsByGroup.values().stream().flatMap(List::stream).distinct().toList());
    List<SuccessionFinding> findings = new ArrayList<>();
    for (Group group : relevant) {
      boolean somebodyCanAct =
          stewardsByGroup.getOrDefault(group.getId(), List.of()).stream()
              .anyMatch(active::contains);
      if (!somebodyCanAct) {
        findings.add(findingOf(group));
      }
    }
    return findings;
  }

  /** A protected group is named by its protection alone (ADR-0036, Entscheidung 9). */
  private static SuccessionFinding findingOf(Group group) {
    return SuccessionFinding.of(
        SuccessionObjectType.GROUP,
        group.getId(),
        group.isProtectedGroup() ? "Geschützte Gruppe" : group.getName(),
        SuccessionAddressee.SYSTEM_ADMINISTRATION);
  }
}
