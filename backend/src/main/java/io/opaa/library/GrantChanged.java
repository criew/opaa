package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event for the {@link AssetGrant} permission-history/audit double bookkeeping (#238/#392,
 * #892): every grant lifecycle change writes one {@link PermissionHistoryService} interval and one
 * audit entry side by side, never one without the other. {@link AssetGrantService} and {@link
 * KnowledgeLibraryService} (the owner-grant it creates alongside a new library) publish exactly one
 * of these per operation instead of calling both writers by hand - forgetting one side is
 * structurally impossible once the publish call itself is present. {@link AuditListener} and {@link
 * PermissionHistoryListener} each react to it with their own half of the write, in an intentionally
 * unspecified order: the two writes touch disjoint tables ({@code asset_grant_history} vs {@code
 * audit_log}) with no dependency between them, so neither listener needs to observe the other's
 * effect.
 *
 * <p>{@link Cause} carries its {@link AuditEventType} directly rather than the caller supplying a
 * separate field the two must agree on - {@code ROLE_CHANGED} paired with {@code
 * ASSET_GRANT_GRANTED} would otherwise compile, a mismatch as silent as the positional-parameter
 * bug this event exists to structurally rule out.
 *
 * <p>Published synchronously via the plain Spring {@code ApplicationEventPublisher} (not
 * {@code @TransactionalEventListener}) from within the publisher's own transaction, so both
 * listeners run in that same transaction: a rollback of the triggering operation rolls both writes
 * back with it, exactly like the direct calls this event replaces (see {@code AuditLogService}'s
 * and {@code PermissionHistoryService}'s class Javadoc for why that join-ambient behaviour is
 * load-bearing).
 *
 * <p>{@code auditBefore}/{@code auditAfter} are precomputed by the publisher (which already builds
 * these small role/expiresAt maps for its own return value) rather than derived here from {@code
 * cause}, so this event stays a plain data carrier and the audit payload shape lives in exactly one
 * place per caller.
 */
public record GrantChanged(
    KnowledgeLibrary library,
    AssetGrant grant,
    Cause cause,
    UUID actorUserId,
    Map<String, Object> auditBefore,
    Map<String, Object> auditAfter) {

  /**
   * Which {@link PermissionHistoryService} writer {@link PermissionHistoryListener} calls, paired
   * 1:1 with the {@link AuditEventType} {@link AuditListener} writes for it - the two can never
   * disagree, since there is only one {@link #auditEventType()} per {@link Cause} value.
   */
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
