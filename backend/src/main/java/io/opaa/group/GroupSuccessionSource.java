package io.opaa.group;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.auth.AccountActivityService;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An internal group without an active steward (#1819, ADR-0036 Entscheidung 6) - including the
 * groups the migration of #1812 turned into internal ones <b>without</b> stewards: exactly the
 * state that changeset 041 left behind on purpose, to be picked up here.
 *
 * <p>Only internal groups: a provider group has no stewards but contact points, and those are named
 * by the system administration (#1821), not derived from anything.
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
  public SuccessionObjectType objectType() {
    return SuccessionObjectType.GROUP;
  }

  @Override
  public List<SuccessionFinding> findingsOf(UUID organizationId) {
    List<SuccessionFinding> findings = new ArrayList<>();
    for (Group group : groups.findByOrganizationId(organizationId)) {
      if (group.isInternal() && !hasActiveSteward(group.getId())) {
        findings.add(findingOf(group));
      }
    }
    return findings;
  }

  @Override
  public Optional<SuccessionFinding> findingFor(UUID groupId) {
    return groups
        .findById(groupId)
        .filter(Group::isInternal)
        .filter(group -> !hasActiveSteward(group.getId()))
        .map(GroupSuccessionSource::findingOf);
  }

  private boolean hasActiveSteward(UUID groupId) {
    Set<UUID> stewardIds =
        Set.copyOf(
            stewards.findByGroupIdOrderByCreatedAtAsc(groupId).stream()
                .map(GroupSteward::getUserId)
                .toList());
    return !accountActivity.activeAmong(stewardIds).isEmpty();
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
