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
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * An internal group without an active steward (#1819, ADR-0036 Entscheidung 6) - including the
 * groups the migration of #1812 turned into internal ones <b>without</b> stewards: exactly the
 * state that changeset 041 left behind on purpose, to be picked up here.
 *
 * <p><b>A provider group counts too, but only while it is protected</b> (#1875): its mark can be
 * set and released by its contact points alone, so a protected provider group without a usable one
 * is frozen - nobody can lift the protection, and no administration may do it for them. An
 * unprotected provider group needs no contact point and appears here for nothing.
 */
@Component
class GroupSuccessionSource implements SuccessionFindingSource {

  private final GroupRepository groups;
  private final GroupStewardRepository stewards;
  private final GroupContactRepository contacts;
  private final AccountActivityService accountActivity;

  GroupSuccessionSource(
      GroupRepository groups,
      GroupStewardRepository stewards,
      GroupContactRepository contacts,
      AccountActivityService accountActivity) {
    this.groups = groups;
    this.stewards = stewards;
    this.contacts = contacts;
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

  /**
   * Four queries for the whole organization - the stewards and the contact points of every group it
   * asks about, and one account query over all of them - rather than that pair per group (#682's
   * rule).
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

  /**
   * The one derivation both entry points read: who may act for a group follows its <b>origin</b> -
   * an internal group's stewards, a provider group's contact points - and a steward of a provider
   * group (which only the stock of #1814 has) can no longer touch its mark, so counting them would
   * hide exactly the frozen group this list exists for.
   */
  private List<SuccessionFinding> openAmong(Collection<Group> candidates) {
    List<Group> relevant =
        candidates.stream().filter(GroupSuccessionSource::needsSomebodyResponsible).toList();
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
    Map<UUID, List<UUID>> contactsByGroup = new HashMap<>();
    for (GroupContact contact : contacts.findByGroupIdIn(groupIds)) {
      contactsByGroup
          .computeIfAbsent(contact.getGroupId(), key -> new ArrayList<>())
          .add(contact.getUserId());
    }
    Set<UUID> active =
        accountActivity.activeAmong(
            Stream.concat(
                    stewardsByGroup.values().stream().flatMap(List::stream),
                    contactsByGroup.values().stream().flatMap(List::stream))
                .distinct()
                .toList());
    List<SuccessionFinding> findings = new ArrayList<>();
    for (Group group : relevant) {
      Map<UUID, List<UUID>> responsibleByGroup =
          group.isInternal() ? stewardsByGroup : contactsByGroup;
      boolean somebodyCanAct =
          responsibleByGroup.getOrDefault(group.getId(), List.of()).stream()
              .anyMatch(active::contains);
      if (!somebodyCanAct) {
        findings.add(findingOf(group));
      }
    }
    return findings;
  }

  /**
   * Whether the group has a body of its own to lose: an internal group always does (its stewards
   * maintain it), a provider group only while it is protected - there the contact points decide
   * that one mark and nothing else.
   */
  private static boolean needsSomebodyResponsible(Group group) {
    return group.isInternal() || group.isProtectedGroup();
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
