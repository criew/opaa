package io.opaa.api;

import io.opaa.api.dto.GroupMemberDisclosureResponse;
import io.opaa.api.dto.SpaceAccessDerivationResponse;
import io.opaa.api.dto.SpaceAddMemberRequest;
import io.opaa.api.dto.SpaceAssetAssociationListResponse;
import io.opaa.api.dto.SpaceAssetAssociationRequest;
import io.opaa.api.dto.SpaceAssetAssociationResponse;
import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceRequest;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.dto.SpaceRoleUpdateRequest;
import io.opaa.api.dto.SpaceTransferOwnershipRequest;
import io.opaa.api.dto.SpaceUpdateRequest;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.PermissionSubject;
import io.opaa.permission.PermissionTransferService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceMemberSeed;
import io.opaa.space.SpaceMemberView;
import io.opaa.space.SpaceOverview;
import io.opaa.space.SpaceService;
import io.opaa.space.SpaceUpdate;
import io.opaa.succession.SuccessionService;
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

@RestController
@RequestMapping("/api/v1/spaces")
public class SpaceController {

  private final SpaceService spaceService;
  private final SpaceAssetAssociationService associationService;
  private final PermissionTransferService transferService;
  private final SuccessionService successionService;

  public SpaceController(
      SpaceService spaceService,
      SpaceAssetAssociationService associationService,
      PermissionTransferService transferService,
      SuccessionService successionService) {
    this.spaceService = spaceService;
    this.associationService = associationService;
    this.transferService = transferService;
    this.successionService = successionService;
  }

  @PostMapping
  public ResponseEntity<SpaceResponse> createSpace(
      @Valid @RequestBody SpaceRequest request, @Caller CurrentUser caller) {
    // #686/#706 review: SpaceService#createSpace associates request.getLibraryIds() itself, in
    // the same transaction as the space row - a library that cannot be associated rolls the whole
    // creation back instead of leaving a half-created space behind.
    Space created = spaceService.createSpace(toSpaceCreation(request), caller);
    SpaceResponse response = SpaceResponseMapper.toResponse(spaceService.detailOf(created, caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{spaceId}/assets")
  public SpaceAssetAssociationListResponse listAssetAssociations(
      @PathVariable UUID spaceId, @Caller CurrentUser caller) {
    return SpaceAssetAssociationResponseMapper.toListResponse(
        associationService.listForSpace(spaceId, caller));
  }

  @PostMapping("/{spaceId}/assets")
  public ResponseEntity<SpaceAssetAssociationResponse> associateAsset(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceAssetAssociationRequest request,
      @Caller CurrentUser caller) {
    SpaceAssetAssociationResponse response =
        SpaceAssetAssociationResponseMapper.toResponse(
            associationService.associate(
                spaceId,
                io.opaa.permission.AssetType.of(request.getAssetType().getValue()),
                request.getAssetId(),
                caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{spaceId}/assets/{assetId}")
  public ResponseEntity<Void> detachAsset(
      @PathVariable UUID spaceId, @PathVariable UUID assetId, @Caller CurrentUser caller) {
    associationService.detach(spaceId, assetId, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping
  public List<SpaceListResponse> listSpaces(@Caller CurrentUser caller) {
    List<SpaceOverview> overviews = spaceService.listSpaces(caller);
    return SpaceResponseMapper.toListResponses(overviews);
  }

  @GetMapping("/{spaceId}")
  public SpaceResponse getSpace(@PathVariable UUID spaceId, @Caller CurrentUser caller) {
    Space space = spaceService.getSpace(spaceId, caller);
    return SpaceResponseMapper.toResponse(
        spaceService.detailOf(space, caller),
        transferService.markOf(Space.ASSET_TYPE, space.getId(), caller).orElse(null),
        successionService.findingFor(SuccessionObjectType.SPACE, space.getId()).orElse(null));
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
    return SpaceResponseMapper.toResponse(spaceService.detailOf(updated, caller));
  }

  @DeleteMapping("/{spaceId}")
  public ResponseEntity<Void> deleteSpace(@PathVariable UUID spaceId, @Caller CurrentUser caller) {
    spaceService.deleteSpace(spaceId, caller);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{spaceId}/archive")
  public SpaceResponse archiveSpace(@PathVariable UUID spaceId, @Caller CurrentUser caller) {
    Space archived = spaceService.archiveSpace(spaceId, caller);
    return SpaceResponseMapper.toResponse(spaceService.detailOf(archived, caller));
  }

  /**
   * The Herleitung "warum bin ich in diesem Space" (#1822, ADR-0036 Entscheidung 9). Without {@code
   * userId} it is about the caller; naming somebody else is reserved for those who manage the
   * membership here, and hides every way through a protected group.
   */
  @GetMapping("/{spaceId}/access-derivation")
  public SpaceAccessDerivationResponse getSpaceAccessDerivation(
      @PathVariable UUID spaceId,
      @RequestParam(name = "userId", required = false) UUID userId,
      @Caller CurrentUser caller) {
    return AccessDerivationResponseMapper.toResponse(
        spaceService.accessDerivation(spaceId, userId, caller));
  }

  @GetMapping("/{spaceId}/members")
  public List<SpaceMemberResponse> listMembers(
      @PathVariable UUID spaceId, @Caller CurrentUser caller) {
    List<SpaceMemberView> members = spaceService.listMembers(spaceId, caller);
    return SpaceResponseMapper.toMemberResponses(members);
  }

  /**
   * The members of a group that is a member of this space (#1880, ADR-0036 Entscheidung 9) - "wer
   * ein Recht gibt, sieht, an wen", behind the same bar as the member list itself.
   */
  @GetMapping("/{spaceId}/members/groups/{groupId}/members")
  public GroupMemberDisclosureResponse listSpaceGroupMembers(
      @PathVariable UUID spaceId,
      @PathVariable UUID groupId,
      @RequestParam(name = "offset", defaultValue = "0") int offset,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @Caller CurrentUser caller) {
    return GroupMemberDisclosureResponseMapper.toResponse(
        spaceService.listGroupMembers(spaceId, groupId, offset, limit, caller));
  }

  @PostMapping("/{spaceId}/members")
  public ResponseEntity<SpaceMemberResponse> addMember(
      @PathVariable UUID spaceId,
      @Valid @RequestBody SpaceAddMemberRequest request,
      @Caller CurrentUser caller) {
    // The subject's organization is the caller's own - a request body never names one (#199).
    PermissionSubject subject =
        new PermissionSubject(
            request.getSubjectType(), request.getSubjectId(), caller.organizationId());
    SpaceMemberResponse response =
        SpaceResponseMapper.toMemberResponse(
            spaceService.addMember(spaceId, subject, request.getRole(), caller));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @DeleteMapping("/{spaceId}/members/{membershipId}")
  public ResponseEntity<Void> removeMember(
      @PathVariable UUID spaceId, @PathVariable UUID membershipId, @Caller CurrentUser caller) {
    spaceService.removeMember(spaceId, membershipId, caller);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{spaceId}/members/{membershipId}/role")
  public SpaceMemberResponse updateMemberRole(
      @PathVariable UUID spaceId,
      @PathVariable UUID membershipId,
      @Valid @RequestBody SpaceRoleUpdateRequest request,
      @Caller CurrentUser caller) {
    return SpaceResponseMapper.toMemberResponse(
        spaceService.updateMemberRole(spaceId, membershipId, request.getRole(), caller));
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
