package io.opaa.api;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.MyCapabilitiesResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupService;
import io.opaa.permission.CapabilityService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service endpoints scoped to the caller, deliberately separate from {@link
 * io.opaa.auth.UserInfoController} (which owns {@code /api/v1/auth/me}, the identity endpoint used
 * during login) and from {@link GroupController} (which acts on one named group). {@link #myGroups}
 * exists because the library-creation dialog needs the caller's own group memberships to offer a
 * GROUP owner - {@code GroupService#listMyGroups}'s Javadoc explains why {@code listGroups} cannot
 * serve that purpose; {@link #myCapabilities} for the same reason on the creation dialogs
 * themselves.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

  private final GroupService groupService;
  private final CapabilityService capabilityService;

  public MeController(GroupService groupService, CapabilityService capabilityService) {
    this.groupService = groupService;
    this.capabilityService = capabilityService;
  }

  @GetMapping("/groups")
  public List<GroupListResponse> myGroups(@Caller CurrentUser caller) {
    List<GroupOverview> groups = groupService.listMyGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }

  /**
   * The internal groups the caller is responsible for - what "Meine Gruppen" shows (#1814, ADR-0036
   * Entscheidung 4). Separate from {@link #myGroups} because a steward is no member: that endpoint
   * answers "which groups may I own a library through", this one "which groups do I maintain".
   */
  @GetMapping("/stewarded-groups")
  public List<GroupListResponse> myStewardedGroups(@Caller CurrentUser caller) {
    List<GroupOverview> groups = groupService.listStewardedGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }

  /**
   * The provider groups the caller is the contact point of (#1875, ADR-0036 Entscheidung 9) - where
   * the protection mark of such a group is set and released. Being a contact point is no
   * maintenance right, which is why this is a list of its own rather than part of the stewarded
   * groups above.
   */
  @GetMapping("/contacted-groups")
  public List<GroupListResponse> myContactedGroups(@Caller CurrentUser caller) {
    List<GroupOverview> groups = groupService.listContactedGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }

  /**
   * The caller's own capabilities, so the interface can explain a missing creation right instead of
   * hiding the button (ADR-0036, Entscheidung 5). Read per request, never from the token: a
   * withdrawal takes effect without a new sign-in.
   */
  @GetMapping("/capabilities")
  public MyCapabilitiesResponse myCapabilities(@Caller CurrentUser caller) {
    return CapabilityResponseMapper.toMyCapabilities(capabilityService.capabilitiesOf(caller));
  }
}
