package io.opaa.api;

import io.opaa.api.dto.GroupAddMemberRequest;
import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupRequest;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.dto.GroupUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.group.GroupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/groups")
public class GroupController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final GroupService groupService;
  private final UserService userService;

  public GroupController(GroupService groupService, UserService userService) {
    this.groupService = groupService;
    this.userService = userService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<GroupListResponse> listGroups(@AuthenticationPrincipal Jwt jwt) {
    return groupService.listGroups(currentUser(jwt).getId());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping
  public ResponseEntity<GroupResponse> createGroup(
      @Valid @RequestBody GroupRequest request, @AuthenticationPrincipal Jwt jwt) {
    GroupResponse response = groupService.createGroup(request, currentUser(jwt).getId());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/{groupId}")
  public GroupResponse getGroup(@PathVariable UUID groupId, @AuthenticationPrincipal Jwt jwt) {
    return groupService.getGroup(groupId, currentUser(jwt).getId());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/{groupId}")
  public GroupResponse updateGroup(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return groupService.updateGroup(groupId, request, currentUser(jwt).getId());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{groupId}")
  public ResponseEntity<Void> deleteGroup(
      @PathVariable UUID groupId, @AuthenticationPrincipal Jwt jwt) {
    groupService.deleteGroup(groupId, currentUser(jwt).getId());
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/{groupId}/members")
  public List<GroupMemberResponse> listMembers(
      @PathVariable UUID groupId, @AuthenticationPrincipal Jwt jwt) {
    return groupService.listMembers(groupId, currentUser(jwt).getId());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{groupId}/members")
  public ResponseEntity<GroupMemberResponse> addMember(
      @PathVariable UUID groupId,
      @Valid @RequestBody GroupAddMemberRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    GroupMemberResponse response =
        groupService.addMember(groupId, request.getUserId(), currentUser(jwt).getId());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{groupId}/members/{userId}")
  public ResponseEntity<Void> removeMember(
      @PathVariable UUID groupId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    groupService.removeMember(groupId, userId, currentUser(jwt).getId());
    return ResponseEntity.noContent().build();
  }

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
  }
}
