package io.opaa.asset;

import java.util.Map;
import java.util.UUID;

/**
 * Domain event for the shell's own double bookkeeping (#238/#892): creating an asset writes one
 * {@link AssetVisibilityHistoryService} interval and one audit entry, side by side - {@link
 * AssetHistoryListener} and {@link AssetAuditListener} each write their half. Published only by
 * {@link AssetShellService}, synchronously and inside its transaction, so both writes roll back
 * with the operation - the same contract as {@link AssetGrantChanged}.
 *
 * <p>The audit event of {@link Cause#CREATED} is the type's own ({@link
 * AssetTypeDefinition#createdAuditEventType()}).
 */
public record AssetChanged(
    Asset asset,
    Cause cause,
    UUID actorUserId,
    Map<String, Object> auditBefore,
    Map<String, Object> auditAfter) {

  public enum Cause {
    CREATED
  }
}
