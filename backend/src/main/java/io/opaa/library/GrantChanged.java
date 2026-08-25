package io.opaa.library;

import io.opaa.audit.AuditEventType;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event for the {@link AssetGrant} permission-history/audit double bookkeeping (#238/#392,
 * #892): every grant lifecycle change writes one {@link PermissionHistoryService} interval and one
 * audit entry side by side, never one without the other. {@link AssetGrantService} and {@link
 * KnowledgeLibraryService} (the owner-grant it creates alongside a new library) publish exactly one
 * of these per operation instead of calling both writers by hand - forgetting one side is
 * structurally impossible once the publish call itself is present. {@link AuditListener} and {@link
 * PermissionHistoryListener} each react to it with their own half of the write.
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
    AuditEventType auditEventType,
    Map<String, Object> auditBefore,
    Map<String, Object> auditAfter) {

  /** Which {@link PermissionHistoryService} writer {@link PermissionHistoryListener} calls. */
  public enum Cause {
    GRANTED,
    ROLE_CHANGED,
    REVOKED
  }
}
