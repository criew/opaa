package io.opaa.permission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionTransferRepository extends JpaRepository<PermissionTransfer, UUID> {

  Optional<PermissionTransfer> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * The transfers that touched this object, most recent first - the sharing view takes the first.
   * Ordered by {@code performedAt}, not by the object row's own {@code createdAt}: the operation's
   * instant is the one the view names.
   */
  @Query(
      "select t from PermissionTransfer t, PermissionTransferObject o "
          + "where o.transferId = t.id and o.assetType = :assetType and o.assetId = :assetId "
          + "order by t.performedAt desc")
  List<PermissionTransfer> findTransfersOfAsset(
      @Param("assetType") AssetType assetType, @Param("assetId") UUID assetId, Pageable pageable);
}
