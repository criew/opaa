package io.opaa.api;

import io.opaa.api.dto.SpaceAddMemberRequest;
import io.opaa.api.dto.SpaceLibraryAssociationListResponse;
import io.opaa.api.dto.SpaceLibraryAssociationRequest;
import io.opaa.api.dto.SpaceLibraryAssociationResponse;
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
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceMemberSeed;
import io.opaa.space.SpaceMemberView;
import io.opaa.space.SpaceOverview;
import io.opaa.space.SpaceService;
import io.opaa.space.SpaceUpdate;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
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
  private final SpaceAssetAssociationService associationService;
  private final UserService userService;

  public SpaceController(
      SpaceService spaceService,
      SpaceAssetAssociationService associationService,
      UserService userService) {
    this.spaceService = spaceService;
    this.associationService = associationService;
    this.userService = userService;
  }

  @PostMapping
  public ResponseEntity<SpaceResponse> createSpace(
      @Valid @RequestBody SpaceRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    boolean systemAdmin = currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN;
    // #686/#706 review: SpaceService#createSpace associates request.getLibraryIds() itself, in
    // the same transaction as the space row - a library that cannot be associated rolls the whole
    // creation back instead of leaving a half-created space behind.
    Space created =
        spaceService.createSpace(toSpaceCreation(request), currentUser.getId(), systemAdmin);
    SpaceResponse response = SpaceResponseMapper.toResponse(created, currentUser.getId());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{spaceId}/libraries")
  public SpaceLibraryAssociationListResponse listLibraryAssociations(
      @PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return SpaceLibraryAssociationResponseMapper.toListResponse(
        associationService.listForSpace(
            spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
  }

  @PostMapping("/{spaceId}/libraries")
  public ResponseEntity<SpaceLibraryAssociationResponse> associateLibrary(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceLibraryAssociationRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    SpaceLibraryAssociationResponse response =
        SpaceLibraryAssociationResponseMapper.toResponse(
            associationService.associate(
                spaceId,
                request.getLibraryId(),
                currentUser.getId(),
                currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{spaceId}/libraries/{libraryId}")
  public ResponseEntity<Void> detachLibrary(
      @PathVariable UUID spaceId, @PathVariable UUID libraryId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    associationService.detach(
        spaceId,
        libraryId,
        currentUser.getId(),
        currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  @GetMapping
  public List<SpaceListResponse> listSpaces(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    List<SpaceOverview> overviews =
        spaceService.listSpaces(
            currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return SpaceResponseMapper.toListResponses(overviews, currentUser.getId());
  }

  @GetMapping("/{spaceId}")
  public SpaceResponse getSpace(@PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    Space space =
        spaceService.getSpace(
            spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return SpaceResponseMapper.toResponse(space, currentUser.getId());
  }

  @PutMapping("/{spaceId}")
  public SpaceResponse updateSpace(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    Space updated =
        spaceService.updateSpace(
            spaceId,
            new SpaceUpdate(request.getName(), request.getDescription(), request.getVisibility()),
            currentUser.getId(),
            currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return SpaceResponseMapper.toResponse(updated, currentUser.getId());
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
  public SpaceResponse archiveSpace(@PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    Space archived =
        spaceService.archiveSpace(
            spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return SpaceResponseMapper.toResponse(archived, currentUser.getId());
  }

  @GetMapping("/{spaceId}/members")
  public List<SpaceMemberResponse> listMembers(
      @PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    List<SpaceMemberView> members =
        spaceService.listMembers(
            spaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return SpaceResponseMapper.toMemberResponses(members);
  }

  @PostMapping("/{spaceId}/members")
  public ResponseEntity<SpaceMemberResponse> addMember(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceAddMemberRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    SpaceMemberResponse response =
        SpaceResponseMapper.toMemberResponse(
            spaceService.addMember(
                spaceId, request.getUserId(), request.getRole(), currentUser.getId()));
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
    return SpaceResponseMapper.toMemberResponse(
        spaceService.updateMemberRole(spaceId, userId, request.getRole(), currentUser.getId()));
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

  private SpaceCreation toSpaceCreation(SpaceRequest request) {
    List<SpaceMemberSeed> initialMembers =
        request.getInitialMembers() == null
            ? null
            : request.getInitialMembers().stream()
                .filter(Objects::nonNull)
                .map(member -> new SpaceMemberSeed(member.getUserId(), member.getRole()))
                .toList();
    return new SpaceCreation(
        request.getName(),
        request.getDescription(),
        request.getOwnerId(),
        request.getVisibility(),
        initialMembers,
        request.getLibraryIds());
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
