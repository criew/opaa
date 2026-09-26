package io.opaa.group;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupState;
import io.opaa.auth.CurrentUser;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administration's group list, one page at a time (#1978). Filter and order run over the
 * organization's groups in memory - their number is bounded by the directory sync's group limit -
 * and stewards, contact points and provider details are resolved for the returned page only.
 */
@Service
public class GroupListService {

  /** Declared order of {@link GroupListQuery.Sort#KIND}: the German labels read alphabetically. */
  private static final List<GroupKind> KIND_ORDER =
      List.of(GroupKind.AD_HOC, GroupKind.IDENTITY_PROVIDER, GroupKind.ORG_UNIT);

  /** What needs a decision first; {@link GroupState} itself is declared by precedence. */
  private static final List<GroupState> STATE_ORDER =
      List.of(
          GroupState.DISSOLVED,
          GroupState.PROVIDER_DISABLED,
          GroupState.UNMAINTAINED,
          GroupState.NOT_RELEASED,
          GroupState.ACTIVE);

  private final GroupRepository groupRepository;
  private final GroupService groupService;

  public GroupListService(GroupRepository groupRepository, GroupService groupService) {
    this.groupRepository = groupRepository;
    this.groupService = groupService;
  }

  @Transactional(readOnly = true)
  public GroupPage pageGroups(CurrentUser caller, GroupListQuery query) {
    List<Group> groups =
        groupRepository.findByOrganizationIdWithMemberships(caller.organizationId());
    Map<UUID, GroupProviderView> providers = groupService.providerViewsOf(groups);
    List<Row> matching =
        groups.stream()
            .map(group -> new Row(group, providers.get(group.getProviderId())))
            .filter(row -> query.matches(row.group(), row.provider(), row.state()))
            .sorted(order(query))
            .toList();
    int from = (int) Math.min((long) query.page() * query.size(), matching.size());
    int to = Math.min(from + query.size(), matching.size());
    List<Group> page = matching.subList(from, to).stream().map(Row::group).toList();
    return new GroupPage(
        groupService.toOverviews(page, providers), matching.size(), query.page(), query.size());
  }

  private record Row(Group group, GroupProviderView provider) {
    GroupState state() {
      return GroupStates.stateOf(group, provider);
    }
  }

  /** The chosen field first, then the name and the id - a stable order across pages. */
  private static Comparator<Row> order(GroupListQuery query) {
    Collator collator = Collator.getInstance(Locale.GERMAN);
    collator.setStrength(Collator.SECONDARY);
    Comparator<Row> byName = Comparator.comparing(row -> row.group().getName(), collator);
    Comparator<Row> primary =
        switch (query.sort()) {
          case NAME -> byName;
          case KIND -> Comparator.comparingInt(row -> KIND_ORDER.indexOf(row.group().getKind()));
          case ORIGIN ->
              Comparator.<Row, Boolean>comparing(row -> row.provider() != null)
                  .thenComparing(
                      row -> row.provider() == null ? "" : row.provider().displayName(), collator);
          case MEMBER_COUNT -> Comparator.comparingInt(row -> row.group().getMemberships().size());
          case STATE -> Comparator.comparingInt(row -> STATE_ORDER.indexOf(row.state()));
          case CREATED_AT -> Comparator.comparing(row -> row.group().getCreatedAt());
        };
    if (query.descending()) {
      primary = primary.reversed();
    }
    return primary.thenComparing(byName).thenComparing(row -> row.group().getId());
  }
}
