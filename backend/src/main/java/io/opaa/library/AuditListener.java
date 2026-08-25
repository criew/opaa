package io.opaa.library;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The audit half of {@link GrantChanged}/{@link LibraryChanged}'s double bookkeeping - see their
 * Javadoc for the transaction contract shared with {@link PermissionHistoryListener}. A default
 * {@code @EventListener} (not {@code @TransactionalEventListener}): it runs synchronously, in the
 * publisher's own transaction, so a rollback of the triggering operation also rolls this write back
 * (#892 - the issue's transaction semantics are unchanged from the direct calls this replaces).
 */
@Component
class AuditListener {

  private final AuditEventRecorder auditEventRecorder;

  AuditListener(AuditEventRecorder auditEventRecorder) {
    this.auditEventRecorder = auditEventRecorder;
  }

  @EventListener
  void onGrantChanged(GrantChanged event) {
    AssetGrant grant = event.grant();
    AuditSubjectKind subjectKind =
        grant.getSubjectType() == PermissionSubjectType.USER
            ? AuditSubjectKind.USER
            : AuditSubjectKind.GROUP;
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(event.library().getOrganizationId())
            .actor(event.actorUserId())
            .type(event.cause().auditEventType())
            .object(
                AuditObjectType.KNOWLEDGE_LIBRARY,
                event.library().getId(),
                event.library().getName())
            .subject(subjectKind, grant.getSubjectId())
            .before(event.auditBefore())
            .after(event.auditAfter())
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  @EventListener
  void onLibraryChanged(LibraryChanged event) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(event.library().getOrganizationId())
            .actor(event.actorUserId())
            .type(event.cause().auditEventType())
            .object(
                AuditObjectType.KNOWLEDGE_LIBRARY,
                event.library().getId(),
                event.library().getName())
            .before(event.auditBefore())
            .after(event.auditAfter())
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
