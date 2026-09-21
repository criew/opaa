package io.opaa.api;

import io.opaa.api.dto.GroupAddMemberRequest;
import io.opaa.api.dto.GroupAppointStewardRequest;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupProtectionRequest;
import io.opaa.api.dto.GroupReleaseRequest;
import io.opaa.api.dto.GroupRequest;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.dto.GroupStewardResponse;
import io.opaa.api.dto.GroupUpdateRequest;
import io.opaa.api.dto.SelectableGroupResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.group.GroupCreation;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupMemberView;
import io.opaa.group.GroupService;
import io.opaa.group.GroupStewardView;
import io.opaa.group.GroupUpdate;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The group API without {@code /admin} (#1814, ADR-0036 Entscheidung 4): who may act is decided per
 * group inside {@link GroupService}, not by a role at the door. There is therefore no
 * {@code @PreAuthorize} here - a role annotation would answer {@code 403} where the service
 * deliberately answers {@code 404}, and would lock out the stewards this endpoint exists for. The
 * one group path left under {@code /admin} is the list of every group of the organization, in
 * {@link AdminGroupController}.
 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

  private final GroupService groupService;

  public GroupController(GroupService groupService) {
    this.groupService = groupService;
  }

  @PostMapping
  public ResponseEntity<GroupResponse> createGroup(
      @Valid @RequestBody GroupRequest request, @Caller CurrentUser caller) {
    GroupDetail created =
        groupService.createGroup(
            new GroupCreation(request.getName(), request.getDescription()), caller);
    return ResponseEntity.status(HttpStatus.CREATED).body(GroupResponseMapper.toResponse(created));
  }

  /**
   * Mapped before {@code /{groupId}} by the literal-over-variable precedence of Spring's path
   * matching, so "selectable" is never read as a group id.
   */
  @GetMapping("/selectable")
  public List<SelectableGroupResponse> searchSelectableGroups(
      @RequestParam(name = "query", required = false) String query, @Caller CurrentUser caller) {
    return GroupResponseMapper.toSelectableResponses(
        groupService.searchSelectableGroups(query, caller));
  }

  @GetMapping("/{groupId}")
  public GroupResponse getGroup(@PathVariable UUID groupId, @Caller CurrentUser caller) {
    return GroupResponseMapper.toResponse(groupService.getGroup(groupId, caller));
  }

  @PutMapping("/{groupId}")
  public GroupResponse updateGroup(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupUpdateRequest request,
      @Caller CurrentUser caller) {
    GroupDetail updated =
        groupService.updateGroup(
            groupId, new GroupUpdate(request.getName(), request.getDescription()), caller);
    return GroupResponseMapper.toResponse(updated);
  }

  @DeleteMapping("/{groupId}")
  public ResponseEntity<Void> deleteGroup(@PathVariable UUID groupId, @Caller CurrentUser caller) {
    groupService.deleteGroup(groupId, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{groupId}/members")
  public List<GroupMemberResponse> listMembers(
      @PathVariable UUID groupId, @Caller CurrentUser caller) {
    List<GroupMemberView> members = groupService.listMembers(groupId, caller);
    return GroupResponseMapper.toMemberResponses(members);
  }

  @PostMapping("/{groupId}/members")
  public ResponseEntity<GroupMemberResponse> addMember(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupAddMemberRequest request,
      @Caller CurrentUser caller) {
    GroupMemberResponse response =
        GroupResponseMapper.toMemberResponse(
            groupService.addMember(groupId, request.getUserId(), caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{groupId}/members/{userId}")
  public ResponseEntity<Void> removeMember(
      @PathVariable UUID groupId, @PathVariable UUID userId, @Caller CurrentUser caller) {
    groupService.removeMember(groupId, userId, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{groupId}/stewards")
  public List<GroupStewardResponse> listStewards(
      @PathVariable UUID groupId, @Caller CurrentUser caller) {
    List<GroupStewardView> stewards = groupService.listStewards(groupId, caller);
    return GroupResponseMapper.toStewardResponses(stewards);
  }

  @PostMapping("/{groupId}/stewards")
  public ResponseEntity<GroupStewardResponse> appointSteward(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupAppointStewardRequest request,
      @Caller CurrentUser caller) {
    GroupStewardResponse response =
        GroupResponseMapper.toStewardResponse(
            groupService.appointSteward(groupId, request.getUserId(), caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{groupId}/stewards/{userId}")
  public ResponseEntity<Void> dismissSteward(
      @PathVariable UUID groupId, @PathVariable UUID userId, @Caller CurrentUser caller) {
    groupService.dismissSteward(groupId, userId, caller);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{groupId}/release")
  public GroupResponse setRelease(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupReleaseRequest request,
      @Caller CurrentUser caller) {
    return GroupResponseMapper.toResponse(
        groupService.setRelease(groupId, request.getReleasedForUse(), caller));
  }

  @PutMapping("/{groupId}/protection")
  public GroupResponse setProtection(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupProtectionRequest request,
      @Caller CurrentUser caller) {
    return GroupResponseMapper.toResponse(
        groupService.setProtection(groupId, request.getProtectedGroup(), caller));
  }
}
