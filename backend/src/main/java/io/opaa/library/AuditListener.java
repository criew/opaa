package io.opaa.library;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The audit half of {@link LibraryChanged}'s double bookkeeping. A plain {@code @EventListener}: it
 * runs in the publisher's transaction and rolls back with it.
 */
@Component
class AuditListener {

  private final AuditEventRecorder auditEventRecorder;

  AuditListener(AuditEventRecorder auditEventRecorder) {
    this.auditEventRecorder = auditEventRecorder;
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
