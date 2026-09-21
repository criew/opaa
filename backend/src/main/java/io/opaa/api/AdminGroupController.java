package io.opaa.api;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

  public AdminGroupController(GroupService groupService) {
    this.groupService = groupService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<GroupListResponse> listGroups(@Caller CurrentUser caller) {
    List<GroupOverview> groups = groupService.listGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }
}
