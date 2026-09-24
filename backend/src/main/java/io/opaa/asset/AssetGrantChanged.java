package io.opaa.asset;

import io.opaa.api.types.AuditEventType;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.PermissionHistoryService;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event for the {@link AssetGrant} permission-history/audit double bookkeeping (#238/#392,
 * #892): every grant lifecycle change writes one {@link PermissionHistoryService} interval and one
 * audit entry side by side, never one without the other. Every writer of a grant publishes exactly
 * one of these per change; {@link AssetAuditListener} and {@link AssetHistoryListener} each write
 * their half, in an unspecified order - the two writes touch disjoint tables.
 *
 * <p>{@link Cause} carries its {@link AuditEventType}, so a cause and an event type can never
 * disagree.
 *
 * <p>Published synchronously from within the publisher's own transaction, so both listeners run in
 * it: a rollback of the triggering operation rolls both writes back.
 *
 * <p>{@code auditBefore}/{@code auditAfter} are precomputed by the publisher, so this event stays a
 * plain data carrier and the audit payload shape lives in exactly one place per caller.
 */
public record AssetGrantChanged(
    Asset asset,
    AssetGrant grant,
    Cause cause,
    UUID actorUserId,
    Map<String, Object> auditBefore,
    Map<String, Object> auditAfter) {

  /** Which history writer runs, paired 1:1 with the audit event type written for it. */
  public enum Cause {
    GRANTED(AuditEventType.ASSET_GRANT_GRANTED),
    ROLE_CHANGED(AuditEventType.ASSET_GRANT_CHANGED),
    REVOKED(AuditEventType.ASSET_GRANT_REVOKED);

    private final AuditEventType auditEventType;

    Cause(AuditEventType auditEventType) {
      this.auditEventType = auditEventType;
    }

    public AuditEventType auditEventType() {
      return auditEventType;
    }
  }
}
