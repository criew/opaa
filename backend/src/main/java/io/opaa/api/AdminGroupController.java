package io.opaa.api;

import io.opaa.api.dto.GroupAppointContactRequest;
import io.opaa.api.dto.GroupContactResponse;
import io.opaa.api.dto.GroupEffectsResponse;
import io.opaa.api.dto.GroupListResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.group.GroupContactService;
import io.opaa.group.GroupEffectsService;
import io.opaa.group.GroupEffectsView;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  private final GroupContactService groupContactService;

  public AdminGroupController(
      GroupService groupService,
      GroupEffectsService groupEffectsService,
      GroupContactService groupContactService) {
    this.groupService = groupService;
    this.groupEffectsService = groupEffectsService;
    this.groupContactService = groupContactService;
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
      @Caller CurrentUser caller,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(name = "groupId", required = false) List<UUID> groupIds) {
    List<GroupEffectsView> effects =
        groupEffectsService.listEffects(
            caller, providerId, groupIds == null ? List.of() : groupIds);
    return GroupEffectsResponseMapper.toResponses(effects);
  }

  /**
   * The contact points of a provider group (#1875, ADR-0036 Entscheidung 9). Naming them is a
   * Verwaltungsakt of the administration - which is why these three paths stay under {@code /admin}
   * - while the act they entitle to, the protection mark, lives in {@link GroupController} and is
   * closed to the administration itself.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{groupId}/contacts")
  public ResponseEntity<GroupContactResponse> appointGroupContact(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupAppointContactRequest request,
      @Caller CurrentUser caller) {
    GroupContactResponse response =
        GroupResponseMapper.toContactResponse(
            groupContactService.appointContact(groupId, request.getUserId(), caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{groupId}/contacts/{userId}")
  public ResponseEntity<Void> dismissGroupContact(
      @PathVariable UUID groupId, @PathVariable UUID userId, @Caller CurrentUser caller) {
    groupContactService.dismissContact(groupId, userId, caller);
    return ResponseEntity.noContent().build();
  }
}
