package io.opaa.permission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionTransferRepository
    extends JpaRepository<PermissionTransfer, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "permission_transfers";
  }

  /**
   * This record's part of the retention deletion (ADR-0036, Entscheidung 8). <b>A transfer is an
   * event at one instant, not an interval</b>, so the cutoff is compared against {@code
   * performed_at} - the one boundary every interval of that transfer carries. Without this, the
   * name snapshot of the source group and the note at every touched object would outlive the
   * intervals they belong to, unbounded; that permanence is exactly what Entscheidung 10 argues
   * against for a person as the source.
   *
   * <p>{@code permission_transfer_objects} follows through {@code ON DELETE CASCADE}, and the
   * {@code transfer_id} of the history rows through {@code ON DELETE SET NULL} - the intervals stay
   * and keep answering, they only lose the id that grouped them.
   */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from PermissionTransfer t where t.performedAt < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<PermissionTransfer> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * The transfers that touched this object, most recent first - the sharing view takes the first.
   * Ordered by {@code performedAt}, not by the object row's own {@code createdAt}: the operation's
   * instant is the one the view names. Carries the organization boundary itself rather than
   * trusting the asset id to have been checked already.
   */
  @Query(
      "select t from PermissionTransfer t, PermissionTransferObject o "
          + "where o.transferId = t.id and o.assetType = :assetType and o.assetId = :assetId "
          + "and t.organizationId = :organizationId "
          + "order by t.performedAt desc")
  List<PermissionTransfer> findTransfersOfAsset(
      @Param("assetType") AssetType assetType,
      @Param("assetId") UUID assetId,
      @Param("organizationId") UUID organizationId,
      Pageable pageable);
}
