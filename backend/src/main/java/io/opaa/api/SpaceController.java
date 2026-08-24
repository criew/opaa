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
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces")
public class SpaceController {

  private final SpaceService spaceService;
  private final SpaceAssetAssociationService associationService;

  public SpaceController(
      SpaceService spaceService, SpaceAssetAssociationService associationService) {
    this.spaceService = spaceService;
    this.associationService = associationService;
  }

  @PostMapping
  public ResponseEntity<SpaceResponse> createSpace(
      @Valid @RequestBody SpaceRequest request, @Caller CurrentUser caller) {
    // #686/#706 review: SpaceService#createSpace associates request.getLibraryIds() itself, in
    // the same transaction as the space row - a library that cannot be associated rolls the whole
    // creation back instead of leaving a half-created space behind.
    Space created = spaceService.createSpace(toSpaceCreation(request), caller);
    SpaceResponse response = SpaceResponseMapper.toResponse(created, caller.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{spaceId}/libraries")
  public SpaceLibraryAssociationListResponse listLibraryAssociations(
      @PathVariable UUID spaceId, @Caller CurrentUser caller) {
    return SpaceLibraryAssociationResponseMapper.toListResponse(
        associationService.listForSpace(spaceId, caller));
  }

  @PostMapping("/{spaceId}/libraries")
  public ResponseEntity<SpaceLibraryAssociationResponse> associateLibrary(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceLibraryAssociationRequest request,
      @Caller CurrentUser caller) {
    SpaceLibraryAssociationResponse response =
        SpaceLibraryAssociationResponseMapper.toResponse(
            associationService.associate(spaceId, request.getLibraryId(), caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{spaceId}/libraries/{libraryId}")
  public ResponseEntity<Void> detachLibrary(
      @PathVariable UUID spaceId, @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    associationService.detach(spaceId, libraryId, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping
  public List<SpaceListResponse> listSpaces(@Caller CurrentUser caller) {
    List<SpaceOverview> overviews = spaceService.listSpaces(caller);
    return SpaceResponseMapper.toListResponses(overviews, caller.id());
  }

  @GetMapping("/{spaceId}")
  public SpaceResponse getSpace(@PathVariable UUID spaceId, @Caller CurrentUser caller) {
    Space space = spaceService.getSpace(spaceId, caller);
    return SpaceResponseMapper.toResponse(space, caller.id());
  }

  @PutMapping("/{spaceId}")
  public SpaceResponse updateSpace(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceUpdateRequest request,
      @Caller CurrentUser caller) {
    Space updated =
        spaceService.updateSpace(
            spaceId,
            new SpaceUpdate(request.getName(), request.getDescription(), request.getVisibility()),
            caller);
    return SpaceResponseMapper.toResponse(updated, caller.id());
  }

  @DeleteMapping("/{spaceId}")
  public ResponseEntity<Void> deleteSpace(@PathVariable UUID spaceId, @Caller CurrentUser caller) {
    spaceService.deleteSpace(spaceId, caller);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{spaceId}/archive")
  public SpaceResponse archiveSpace(@PathVariable UUID spaceId, @Caller CurrentUser caller) {
    Space archived = spaceService.archiveSpace(spaceId, caller);
    return SpaceResponseMapper.toResponse(archived, caller.id());
  }

  @GetMapping("/{spaceId}/members")
  public List<SpaceMemberResponse> listMembers(
      @PathVariable UUID spaceId, @Caller CurrentUser caller) {
    List<SpaceMemberView> members = spaceService.listMembers(spaceId, caller);
    return SpaceResponseMapper.toMemberResponses(members);
  }

  @PostMapping("/{spaceId}/members")
  public ResponseEntity<SpaceMemberResponse> addMember(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceAddMemberRequest request,
      @Caller CurrentUser caller) {
    SpaceMemberResponse response =
        SpaceResponseMapper.toMemberResponse(
            spaceService.addMember(spaceId, request.getUserId(), request.getRole(), caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{spaceId}/members/{userId}")
  public ResponseEntity<Void> removeMember(
      @PathVariable UUID spaceId, @PathVariable UUID userId, @Caller CurrentUser caller) {
    spaceService.removeMember(spaceId, userId, caller);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{spaceId}/members/{userId}/role")
  public SpaceMemberResponse updateMemberRole(
      @PathVariable UUID spaceId,
      @PathVariable UUID userId,
      @Valid @RequestBody SpaceRoleUpdateRequest request,
      @Caller CurrentUser caller) {
    return SpaceResponseMapper.toMemberResponse(
        spaceService.updateMemberRole(spaceId, userId, request.getRole(), caller));
  }

  @PostMapping("/{spaceId}/transfer-ownership")
  public ResponseEntity<Void> transferOwnership(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceTransferOwnershipRequest request,
      @Caller CurrentUser caller) {
    spaceService.transferOwnership(spaceId, request.getUserId(), caller);
    return ResponseEntity.noContent().build();
  }

  private SpaceCreation toSpaceCreation(SpaceRequest request) {
    // A null element is passed through unchanged - SpaceService#appendInitialMemberships is the
    // single place that skips it, not this mapping step too.
    List<SpaceMemberSeed> initialMembers =
        request.getInitialMembers() == null
            ? null
            : request.getInitialMembers().stream()
                .map(
                    member ->
                        member == null
                            ? null
                            : new SpaceMemberSeed(member.getUserId(), member.getRole()))
                .toList();
    return new SpaceCreation(
        request.getName(),
        request.getDescription(),
        request.getOwnerId(),
        request.getVisibility(),
        initialMembers,
        request.getLibraryIds());
  }
}
