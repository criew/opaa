package io.opaa.api;

import io.opaa.api.dto.GroupEffectsResponse;
import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupPageResponse;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupOrigin;
import io.opaa.api.types.GroupState;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.group.GroupEffectsService;
import io.opaa.group.GroupEffectsView;
import io.opaa.group.GroupListQuery;
import io.opaa.group.GroupListService;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one group path that stayed under {@code /admin} (#1814, ADR-0036 Entscheidung 4): reading
 * every group of the organization regardless of who maintains it is an administrative act, not a
 * stewardship. Everything that acts on a single group lives in {@link GroupController}, where the
 * right is decided per group.
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
public class AdminGroupController {

  private final GroupService groupService;
  private final GroupListService groupListService;
  private final GroupEffectsService groupEffectsService;

  public AdminGroupController(
      GroupService groupService,
      GroupListService groupListService,
      GroupEffectsService groupEffectsService) {
    this.groupService = groupService;
    this.groupListService = groupListService;
    this.groupEffectsService = groupEffectsService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<GroupListResponse> listGroups(@Caller CurrentUser caller) {
    List<GroupOverview> groups = groupService.listGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }

  /** The group list of the administration, searched, filtered, sorted and paged (#1978). */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/page")
  public GroupPageResponse listGroupPage(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) GroupOrigin origin,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(required = false) GroupKind kind,
      @RequestParam(required = false) GroupState state,
      @RequestParam(defaultValue = "name") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + GroupListQuery.DEFAULT_PAGE_SIZE) int size,
      @Caller CurrentUser caller) {
    GroupListQuery listQuery =
        new GroupListQuery(
            query,
            origin,
            providerId,
            kind,
            state,
            AdminListSortParams.groupSortOf(sort),
            AdminListSortParams.descending(direction),
            page,
            size);
    return GroupResponseMapper.toPage(groupListService.pageGroups(caller, listQuery));
  }

  /**
   * "Wo wirkt diese Gruppe", and with {@code providerId} the work list of one provider (ADR-0036,
   * Entscheidung 2) - counts only, so the overview never reads a membership list on the way.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/effects")
  public List<GroupEffectsResponse> listGroupEffects(
      @Caller CurrentUser caller,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(name = "groupId", required = false) List<UUID> groupIds) {
    List<GroupEffectsView> effects =
        groupEffectsService.listEffects(
            caller, providerId, groupIds == null ? List.of() : groupIds);
    return GroupEffectsResponseMapper.toResponses(effects);
  }
}
