package io.opaa.api;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.group.Group;
import io.opaa.group.GroupService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service endpoints scoped to the caller, deliberately separate from {@link
 * io.opaa.auth.UserInfoController} (which owns {@code /api/v1/auth/me}, the identity endpoint used
 * during login) and from {@link GroupController} (whose {@code /api/v1/admin/groups} is
 * system-admin only). {@link #myGroups} exists because the library-creation dialog needs the
 * caller's own group memberships to offer a GROUP owner - {@code GroupService#listMyGroups}'s
 * Javadoc explains why {@code listGroups} cannot serve that purpose.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

  private final GroupService groupService;

  public MeController(GroupService groupService) {
    this.groupService = groupService;
  }

  @GetMapping("/groups")
  public List<GroupListResponse> myGroups(@Caller CurrentUser caller) {
    List<Group> groups = groupService.listMyGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }
}
