package io.opaa.api;

import io.opaa.api.dto.GroupEffectsResponse;
import io.opaa.api.dto.GroupListResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.group.GroupEffectsService;
import io.opaa.group.GroupEffectsView;
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
  private final GroupEffectsService groupEffectsService;

  public AdminGroupController(GroupService groupService, GroupEffectsService groupEffectsService) {
    this.groupService = groupService;
    this.groupEffectsService = groupEffectsService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<GroupListResponse> listGroups(@Caller CurrentUser caller) {
    List<GroupOverview> groups = groupService.listGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }

  /**
   * "Wo wirkt diese Gruppe", and with {@code providerId} the work list of one provider (ADR-0036,
   * Entscheidung 2) - counts only, so the overview never reads a membership list on the way.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/effects")
  public List<GroupEffectsResponse> listGroupEffects(
      @Caller CurrentUser caller, @RequestParam(required = false) UUID providerId) {
    List<GroupEffectsView> effects = groupEffectsService.listEffects(caller, providerId);
    return GroupEffectsResponseMapper.toResponses(effects);
  }
}
