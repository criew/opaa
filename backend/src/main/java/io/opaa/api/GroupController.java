package io.opaa.api;

import io.opaa.api.dto.GroupAddMemberRequest;
import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupRequest;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.dto.GroupUpdateRequest;
import io.opaa.auth.CurrentUser;
import io.opaa.group.Group;
import io.opaa.group.GroupCreation;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupMemberView;
import io.opaa.group.GroupService;
import io.opaa.group.GroupUpdate;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/groups")
public class GroupController {

  private final GroupService groupService;

  public GroupController(GroupService groupService) {
    this.groupService = groupService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<GroupListResponse> listGroups(CurrentUser caller) {
    List<Group> groups = groupService.listGroups(caller);
    return GroupResponseMapper.toListResponses(groups);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping
  public ResponseEntity<GroupResponse> createGroup(
      @Valid @RequestBody GroupRequest request, CurrentUser caller) {
    GroupDetail created =
        groupService.createGroup(
            new GroupCreation(request.getName(), request.getDescription()), caller);
    GroupResponse response = GroupResponseMapper.toResponse(created);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/{groupId}")
  public GroupResponse getGroup(@PathVariable UUID groupId, CurrentUser caller) {
    GroupDetail detail = groupService.getGroup(groupId, caller);
    return GroupResponseMapper.toResponse(detail);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/{groupId}")
  public GroupResponse updateGroup(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupUpdateRequest request,
      CurrentUser caller) {
    GroupDetail updated =
        groupService.updateGroup(
            groupId, new GroupUpdate(request.getName(), request.getDescription()), caller);
    return GroupResponseMapper.toResponse(updated);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{groupId}")
  public ResponseEntity<Void> deleteGroup(@PathVariable UUID groupId, CurrentUser caller) {
    groupService.deleteGroup(groupId, caller);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/{groupId}/members")
  public List<GroupMemberResponse> listMembers(@PathVariable UUID groupId, CurrentUser caller) {
    List<GroupMemberView> members = groupService.listMembers(groupId, caller);
    return GroupResponseMapper.toMemberResponses(members);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{groupId}/members")
  public ResponseEntity<GroupMemberResponse> addMember(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupAddMemberRequest request,
      CurrentUser caller) {
    GroupMemberResponse response =
        GroupResponseMapper.toMemberResponse(
            groupService.addMember(groupId, request.getUserId(), caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{groupId}/members/{userId}")
  public ResponseEntity<Void> removeMember(
      @PathVariable UUID groupId, @PathVariable UUID userId, CurrentUser caller) {
    groupService.removeMember(groupId, userId, caller);
    return ResponseEntity.noContent().build();
  }
}
