package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event for the library's own double bookkeeping (#238/#892, #1731): a change of the release
 * for Fremdzugaenge writes one reach interval through {@code
 * AssetVisibilityHistoryService#recordExternalAccessChanged} and one audit entry, side by side -
 * {@link PermissionHistoryListener} and {@link AuditListener} each write their half. Creation and
 * findability is the asset shell's ({@code io.opaa.asset.AssetChanged}).
 *
 * <p>Published synchronously from within the publisher's transaction, so both writes roll back with
 * it; {@link Cause} carries its {@link AuditEventType}, so the two can never disagree.
 */
public record LibraryChanged(
    KnowledgeLibrary library,
    Cause cause,
    UUID actorUserId,
    Map<String, Object> auditBefore,
    Map<String, Object> auditAfter) {

  public enum Cause {
    EXTERNAL_ACCESS_CHANGED(AuditEventType.ASSET_EXTERNAL_ACCESS_CHANGED);

    private final AuditEventType auditEventType;

    Cause(AuditEventType auditEventType) {
      this.auditEventType = auditEventType;
    }

    public AuditEventType auditEventType() {
      return auditEventType;
    }
  }
}
