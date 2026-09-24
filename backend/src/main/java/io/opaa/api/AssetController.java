package io.opaa.api;

import io.opaa.api.dto.AssetAccessDerivationResponse;
import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.api.dto.AssetOwnershipTransferRequest;
import io.opaa.api.dto.AssetSpaceAssociationListResponse;
import io.opaa.api.dto.GroupMemberDisclosureResponse;
import io.opaa.asset.AssetAccessDerivationService;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetOwnershipTransferService;
import io.opaa.asset.AssetTypes;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.AssetType;
import io.opaa.space.SpaceAssetAssociationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What every asset has, whatever its type (#1899, #1900): its grants, the Herleitung and the spaces
 * it is associated with. An asset is named by type plus id; a malformed or unknown type answers
 * {@code 404} like an unknown asset.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetType}/{assetId}")
public class AssetController {

  private final AssetGrantService grantService;
  private final AssetAccessDerivationService derivationService;
  private final SpaceAssetAssociationService associationService;
  private final AssetOwnershipTransferService ownershipTransferService;
  private final AssetTypes assetTypes;

  public AssetController(
      AssetGrantService grantService,
      AssetAccessDerivationService derivationService,
      SpaceAssetAssociationService associationService,
      AssetOwnershipTransferService ownershipTransferService,
      AssetTypes assetTypes) {
    this.grantService = grantService;
    this.derivationService = derivationService;
    this.associationService = associationService;
    this.ownershipTransferService = ownershipTransferService;
    this.assetTypes = assetTypes;
  }

  @GetMapping("/grants")
  public List<AssetGrantResponse> listAssetGrants(
      @PathVariable String assetType, @PathVariable UUID assetId, @Caller CurrentUser caller) {
    return AssetGrantResponseMapper.toResponses(
        grantService.listGrants(typeOf(assetType), assetId, caller));
  }

  @PostMapping("/grants")
  public AssetGrantResponse upsertAssetGrant(
      @PathVariable String assetType,
      @PathVariable UUID assetId,
      @Valid @RequestBody AssetGrantRequest request,
      @Caller CurrentUser caller) {
    return AssetGrantResponseMapper.toResponse(
        grantService.upsertGrant(
            typeOf(assetType), assetId, AssetGrantResponseMapper.toUpsert(request), caller));
  }

  @DeleteMapping("/grants/{grantId}")
  public ResponseEntity<Void> revokeAssetGrant(
      @PathVariable String assetType,
      @PathVariable UUID assetId,
      @PathVariable UUID grantId,
      @Caller CurrentUser caller) {
    grantService.revokeGrant(typeOf(assetType), assetId, grantId, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/grants/groups/{groupId}/members")
  public GroupMemberDisclosureResponse listGrantedGroupMembers(
      @PathVariable String assetType,
      @PathVariable UUID assetId,
      @PathVariable UUID groupId,
      @RequestParam(name = "offset", defaultValue = "0") int offset,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @Caller CurrentUser caller) {
    return GroupMemberDisclosureResponseMapper.toResponse(
        grantService.listGroupMembers(typeOf(assetType), assetId, groupId, offset, limit, caller));
  }

  /**
   * The Herleitung "warum sehe ich das" for the caller themselves (#1822, ADR-0036 Entscheidung 9)
   * - no Vollmacht, no protocol entry, and no member of any group disclosed.
   */
  @GetMapping("/access-derivation")
  public AssetAccessDerivationResponse getAssetAccessDerivation(
      @PathVariable String assetType, @PathVariable UUID assetId, @Caller CurrentUser caller) {
    return AccessDerivationResponseMapper.toResponse(
        derivationService.derive(typeOf(assetType), assetId, caller));
  }

  @GetMapping("/spaces")
  public AssetSpaceAssociationListResponse listAssetSpaceAssociations(
      @PathVariable String assetType, @PathVariable UUID assetId, @Caller CurrentUser caller) {
    return SpaceAssetAssociationResponseMapper.toAssetSpaceListResponse(
        associationService.listForAsset(typeOf(assetType), assetId, caller));
  }

  @PostMapping("/transfer-ownership")
  public ResponseEntity<Void> transferAssetOwnership(
      @PathVariable String assetType,
      @PathVariable UUID assetId,
      @Valid @RequestBody AssetOwnershipTransferRequest request,
      @Caller CurrentUser caller) {
    ownershipTransferService.transferOwnership(
        typeOf(assetType), assetId, request.getOwnerType(), request.getOwnerId(), caller);
    return ResponseEntity.noContent().build();
  }

  private AssetType typeOf(String assetType) {
    return assetTypes.require(assetType).assetType();
  }
}
