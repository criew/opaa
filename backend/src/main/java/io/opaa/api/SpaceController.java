package io.opaa.api;

import io.opaa.api.dto.SpaceAddMemberRequest;
import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceRequest;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.dto.SpaceRoleUpdateRequest;
import io.opaa.api.dto.SpaceTransferOwnershipRequest;
import io.opaa.api.dto.SpaceUpdateRequest;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.space.SpaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/spaces")
public class SpaceController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final SpaceService spaceService;
  private final UserService userService;

  public SpaceController(SpaceService spaceService, UserService userService) {
    this.spaceService = spaceService;
    this.userService = userService;
  }

  @PostMapping
  public ResponseEntity<SpaceResponse> createSpace(
      @Valid @RequestBody SpaceRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    SpaceResponse response =
        spaceService.createSpace(
            request, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public List<SpaceListResponse> listSpaces(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return spaceService.listSpaces(currentUser.getId());
  }

  @GetMapping("/{spaceId}")
  public SpaceResponse getSpace(@PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return spaceService.getSpace(
        spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @PutMapping("/{spaceId}")
  public SpaceResponse updateSpace(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return spaceService.updateSpace(
        spaceId,
        request,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @DeleteMapping("/{spaceId}")
  public ResponseEntity<Void> deleteSpace(
      @PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    spaceService.deleteSpace(
        spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{spaceId}/archive")
  public SpaceResponse archiveSpace(
      @PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return spaceService.archiveSpace(
        spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @GetMapping("/{spaceId}/members")
  public List<SpaceMemberResponse> listMembers(
      @PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return spaceService.listMembers(
        spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @PostMapping("/{spaceId}/members")
  public ResponseEntity<SpaceMemberResponse> addMember(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceAddMemberRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    SpaceMemberResponse response =
        spaceService.addMember(
            spaceId, request.getUserId(), request.getRole(), currentUser.getId());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{spaceId}/members/{userId}")
  public ResponseEntity<Void> removeMember(
      @PathVariable UUID spaceId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    spaceService.removeMember(spaceId, userId, currentUser.getId());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{spaceId}/members/{userId}/role")
  public SpaceMemberResponse updateMemberRole(
      @PathVariable UUID spaceId,
      @PathVariable UUID userId,
      @Valid @RequestBody SpaceRoleUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return spaceService.updateMemberRole(spaceId, userId, request.getRole(), currentUser.getId());
  }

  @PostMapping("/{spaceId}/transfer-ownership")
  public ResponseEntity<Void> transferOwnership(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceTransferOwnershipRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    spaceService.transferOwnership(
        spaceId,
        request.getUserId(),
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
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
